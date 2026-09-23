package de.raindancer118.stoneintelligence.worker.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.pipeline.IngestPipeline;
import de.raindancer118.stoneintelligence.worker.platform.ClaimedJob;
import de.raindancer118.stoneintelligence.worker.platform.PlatformApi;
import de.raindancer118.stoneintelligence.worker.platform.PlatformRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verarbeitet einen Job: Dokument holen, StoneAI-Pipeline gegen den gemeinsamen Vault laufen
 * lassen, Ergebnis melden. Voruebergehende Probleme (Anbieter ausgelastet, Netz) werden als
 * "spaeter erneut" gemeldet, alles, was ein neuer Versuch nicht aendert (geschuetztes Dokument,
 * unlesbar), endgueltig.
 */
final class JobProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(JobProcessor.class);
    /** Deutlich kuerzer als die Lease (10 min), damit ein langer Lauf nicht als abgestuerzt gilt. */
    private static final long HEARTBEAT_SECONDS = 120;

    private final PlatformApi platform;
    private final LlmFactory llms;
    private final ServiceModels models;
    private final Supplier<LocalDate> clock;

    JobProcessor(PlatformApi platform, LlmFactory llms, ServiceModels models, Supplier<LocalDate> clock) {
        this.platform = platform;
        this.llms = llms;
        this.models = models;
        this.clock = clock;
    }

    void process(ClaimedJob job) {
        var heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        Path workDir = null;
        try {
            platform.progress(job.jobId(), "Dokument wird gelesen", 5);
            heartbeat.scheduleAtFixedRate(() -> beat(job), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            var content = platform.document(job.jobId());
            if (content == null || content.length == 0) {
                platform.fail(job.jobId(), "Das Dokument war nicht abrufbar", true);
                return;
            }
            workDir = Files.createTempDirectory("stoneai-job-");
            var document = workDir.resolve(safeFileName(job.fileName()));
            Files.write(document, content);

            var model = models.forService(job.service());
            var config = GatewayLlmFactory.configFor(model);
            ConfigSchema.byPath("vault.path").set(config, PlatformNoteStore.ROOT.toString());
            var store = PlatformNoteStore.load(platform, job.vaultId(), job.changeSetId(), job.level());
            var report = IngestPipeline.hosted(config, llms.forService(model), clock).ingestInto(document, store);

            if (report.wasSkipped()) {
                platform.fail(job.jobId(), report.skippedReason(), false);
            } else if (report.notesWritten() == 0 && !report.failures().isEmpty()) {
                platform.fail(job.jobId(), "Die Antworten des Modells waren nicht auswertbar", true);
            } else {
                platform.progress(job.jobId(), summary(report), 100);
                platform.complete(job.jobId());
            }
        } catch (PlatformRefusedException refused) {
            LOG.warn("Job {} von platform-api abgelehnt: {}", job.jobId(), refused.getMessage());
            report(job, refused.getMessage(), false);
        } catch (IOException | RuntimeException problem) {
            LOG.warn("Job {} fehlgeschlagen, wird erneut versucht: {}", job.jobId(), problem.getMessage());
            report(job, message(problem), true);
        } finally {
            heartbeat.shutdownNow();
            delete(workDir);
        }
    }

    /** Fertig heisst nicht vollstaendig: was fehlt, steht in der Meldung (und in der Quellnotiz). */
    static String summary(de.raindancer118.stoneai.pipeline.IngestReport report) {
        var done = report.notesWritten() + " Notizen geschrieben";
        var gaps = report.gaps();
        if (gaps.isEmpty()) {
            return done;
        }
        var listed = String.join("; ", gaps.size() > 5 ? gaps.subList(0, 5) : gaps) + (gaps.size() > 5 ? " …" : "");
        return done + " – nicht verarbeitet: " + listed;
    }

    private void beat(ClaimedJob job) {
        try {
            platform.progress(job.jobId(), "Wird verarbeitet", null);
        } catch (RuntimeException ignored) {
            // Naechster Herzschlag versucht es erneut; faellt er ganz aus, uebernimmt die Lease.
        }
    }

    private void report(ClaimedJob job, String error, boolean retryable) {
        try {
            platform.fail(job.jobId(), error, retryable);
        } catch (RuntimeException unreachable) {
            // platform-api nicht erreichbar oder Job schon beendet - die Lease gibt ihn frei.
            LOG.warn("Fehler fuer Job {} nicht meldbar: {}", job.jobId(), unreachable.getMessage());
        }
    }

    private static String message(Exception problem) {
        var text = problem.getMessage() == null ? problem.getClass().getSimpleName() : problem.getMessage();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** Der Name erscheint in der Quellnotiz; nie ein Pfad. */
    static String safeFileName(String fileName) {
        var name = fileName == null ? "" : fileName.replaceAll(".*[/\\\\]", "").strip();
        return name.isEmpty() || name.startsWith(".") ? "Dokument" + name : name;
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            LOG.warn("Temporaeres Verzeichnis {} nicht geloescht", dir);
        }
    }
}
