package de.tstieh.stoneintelligence.worker.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.stoneai.config.ConfigSchema;
import de.tstieh.stoneintelligence.stoneai.extract.LlmCapacityException;
import de.tstieh.stoneintelligence.stoneai.llm.ResumableLlmClient;
import de.tstieh.stoneintelligence.stoneai.pipeline.IngestPipeline;
import de.tstieh.stoneintelligence.stoneai.pipeline.ProgressSink;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import de.tstieh.stoneintelligence.worker.platform.JobGoneException;
import de.tstieh.stoneintelligence.worker.platform.PlatformApi;
import de.tstieh.stoneintelligence.worker.platform.PlatformRefusedException;
import io.github.raindancer118.aigateway.ProviderCapacity;
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
    /**
     * Deutlich kuerzer als die Lease (10 min), damit ein langer Lauf nicht als abgestuerzt gilt - und
     * kurz genug, dass ein Abbruch im Dashboard auch waehrend einer langen Modellantwort bald greift.
     */
    private static final long HEARTBEAT_SECONDS = 30;
    /** Untergrenze zwischen zwei Fortschrittsmeldungen an platform-api, egal wie viele Abschnitte pro Sekunde durchlaufen. */
    private static final Duration PROGRESS_MIN_INTERVAL = Duration.ofSeconds(3);
    /**
     * So lange bleiben die Antworten eines Jobs liegen, der nicht wiederkam - laenger als platform-api
     * auf Kontingent wartet (7 Tage), danach waeren sie nur noch Kopien fremder Dokumente.
     */
    static final Duration RESUME_KEEP = Duration.ofDays(8);

    private final PlatformApi platform;
    private final LlmFactory llms;
    private final ServiceModels models;
    private final Supplier<LocalDate> clock;
    private final Duration progressInterval;
    private final Path resumeRoot;
    private final Supplier<de.tstieh.stoneintelligence.worker.embed.Embedder> embedder;
    private final LinkingRun.Thresholds thresholds;

    JobProcessor(PlatformApi platform, LlmFactory llms, ServiceModels models, Supplier<LocalDate> clock, Path resumeRoot) {
        this(platform, llms, models, clock, PROGRESS_MIN_INTERVAL, resumeRoot, () -> null, new LinkingRun.Thresholds(1.1, 1.1));
    }

    JobProcessor(PlatformApi platform, LlmFactory llms, ServiceModels models, Supplier<LocalDate> clock, Path resumeRoot,
                 Supplier<de.tstieh.stoneintelligence.worker.embed.Embedder> embedder, LinkingRun.Thresholds thresholds) {
        this(platform, llms, models, clock, PROGRESS_MIN_INTERVAL, resumeRoot, embedder, thresholds);
    }

    JobProcessor(PlatformApi platform, LlmFactory llms, ServiceModels models, Supplier<LocalDate> clock, Duration progressInterval,
                 Path resumeRoot) {
        this(platform, llms, models, clock, progressInterval, resumeRoot, () -> null, new LinkingRun.Thresholds(1.1, 1.1));
    }

    JobProcessor(PlatformApi platform, LlmFactory llms, ServiceModels models, Supplier<LocalDate> clock, Duration progressInterval,
                 Path resumeRoot, Supplier<de.tstieh.stoneintelligence.worker.embed.Embedder> embedder, LinkingRun.Thresholds thresholds) {
        this.embedder = embedder;
        this.thresholds = thresholds;
        this.platform = platform;
        this.llms = llms;
        this.models = models;
        this.clock = clock;
        this.progressInterval = progressInterval;
        this.resumeRoot = resumeRoot;
    }

    /** Wo die Antworten laufender Jobs liegen: {@code STONEAI_RESUME_DIR}, sonst im temporaeren Verzeichnis. */
    static Path resumeRootFrom(Map<String, String> env) {
        var configured = env.get("STONEAI_RESUME_DIR");
        return configured == null || configured.isBlank()
            ? Path.of(System.getProperty("java.io.tmpdir"), "stoneai-resume")
            : Path.of(configured);
    }

    /** Der Job wurde abgebrochen (platform-api sagt 410) - die Pipeline hoert an der naechsten Meldung auf. */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("abgebrochen", null, false, false);
        }
    }

    /**
     * Die Antworten der Modelle bleiben ueber einen Versuch hinaus liegen, solange der Job wiederkommt
     * (Kontingent leer, Anbieter weg): der naechste Versuch fragt nur, was noch fehlt. Fertig,
     * abgebrochen oder endgueltig gescheitert, werden sie geloescht.
     */
    void process(ClaimedJob job) {
        var heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        var cancelled = new AtomicBoolean();
        Path workDir = null;
        var answers = resumeRoot.resolve(job.jobId().toString());
        var keepAnswers = false;
        ResumableLlmClient.purgeOlderThan(resumeRoot, RESUME_KEEP);
        try {
            if (job.isLinking()) {
                heartbeat.scheduleAtFixedRate(() -> beat(job, cancelled), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                new LinkingRun(platform, embedder.get(), thresholds).run(job);
                return;
            }
            var model = models.forService(job.service());
            if (waitedForCapacity(job, model)) {
                keepAnswers = true;
                return;
            }
            platform.progress(job.jobId(), "Dokument wird gelesen", 5);
            heartbeat.scheduleAtFixedRate(() -> beat(job, cancelled), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            var content = platform.document(job.jobId());
            if (content == null || content.length == 0) {
                platform.fail(job.jobId(), "Das Dokument war nicht abrufbar", true);
                return;
            }
            workDir = Files.createTempDirectory("stoneai-job-");
            var document = workDir.resolve(safeFileName(job.fileName()));
            Files.write(document, content);

            var config = GatewayLlmFactory.configFor(model);
            ConfigSchema.byPath("vault.path").set(config, PlatformNoteStore.ROOT.toString());
            var store = PlatformNoteStore.load(platform, job.vaultId(), job.changeSetId(), job.level());
            var llm = new ResumableLlmClient(llms.forService(model), answers);
            var report = IngestPipeline.hosted(config, llm, clock)
                    .withProgress(throttled(job, cancelled))
                    .ingestInto(document, store);
            if (llm.replayed() > 0) {
                LOG.info("Job {}: {} Antworten aus dem letzten Versuch uebernommen", job.jobId(), llm.replayed());
            }

            if (report.wasSkipped()) {
                platform.fail(job.jobId(), report.skippedReason(), false);
            } else if (report.notesWritten() == 0 && !report.failures().isEmpty()) {
                // Dieselben Antworten noch einmal auszuwerten brächte dasselbe - der neue Versuch fragt neu.
                platform.fail(job.jobId(), "Die Antworten des Modells waren nicht auswertbar", true);
            } else {
                platform.progress(job.jobId(), summary(report), 100);
                platform.complete(job.jobId());
            }
        } catch (JobGoneException | Cancelled gone) {
            // Im Dashboard abgebrochen: platform-api hat Geschriebenes schon rueckgaengig gemacht.
            LOG.info("Job {} wurde abgebrochen - Verarbeitung beendet", job.jobId());
        } catch (LlmCapacityException outOfCapacity) {
            if (cancelled.get()) {
                LOG.info("Job {} wurde abgebrochen - Verarbeitung beendet", job.jobId());
                return;
            }
            LOG.info("Job {}: kein Kontingent frei, wartet bis {}", job.jobId(), outOfCapacity.availableAgainAt());
            keepAnswers = true;
            waitForCapacity(job, capacityMessage(outOfCapacity.getMessage()), outOfCapacity.availableAgainAt());
        } catch (PlatformRefusedException refused) {
            if (cancelled.get()) {
                LOG.info("Job {} wurde abgebrochen - Verarbeitung beendet", job.jobId());
                return;
            }
            LOG.warn("Job {} von platform-api abgelehnt: {}", job.jobId(), refused.getMessage());
            report(job, refused.getMessage(), false);
        } catch (IOException | RuntimeException problem) {
            if (cancelled.get()) {
                LOG.info("Job {} wurde abgebrochen - Verarbeitung beendet", job.jobId());
                return;
            }
            LOG.warn("Job {} fehlgeschlagen, wird erneut versucht: {}", job.jobId(), problem.getMessage());
            keepAnswers = true;
            report(job, message(problem), true);
        } finally {
            heartbeat.shutdownNow();
            delete(workDir);
            if (!keepAnswers) {
                ResumableLlmClient.delete(answers);
            }
        }
    }

    /**
     * Wissen die Anbieter schon, dass keiner von ihnen gerade kann, wartet der Job gleich - ohne das
     * Dokument erst zu lesen und zu zerlegen, nur um dann am ersten Modellaufruf zu scheitern.
     */
    private boolean waitedForCapacity(ClaimedJob job, ServiceModels.ServiceModel model) {
        Map<String, ProviderCapacity> capacity;
        try {
            capacity = llms.capacity(model);
        } catch (RuntimeException unknown) {
            return false;
        }
        if (capacity.isEmpty() || !capacity.values().stream().allMatch(ProviderCapacity::exhausted)) {
            return false;
        }
        var back = capacity.values().stream().map(ProviderCapacity::availableAgainAt).filter(java.util.Objects::nonNull)
            .min(Instant::compareTo).orElse(null);
        LOG.info("Job {}: {} ohne Kontingent, wartet bis {}", job.jobId(), CapacityReporter.exhaustedNames(capacity), back);
        waitForCapacity(job, capacityMessage(CapacityReporter.exhaustedNames(capacity)), back);
        return true;
    }

    static String capacityMessage(String detail) {
        return "Kein Kontingent mehr frei (" + detail + ") – nichts wurde geschrieben, der Lauf startet von selbst neu,"
            + " sobald wieder Kapazität da ist, und macht dort weiter, wo er aufgehört hat";
    }

    private void waitForCapacity(ClaimedJob job, String message, Instant availableAt) {
        try {
            platform.waitForCapacity(job.jobId(), message.length() > 500 ? message.substring(0, 500) : message, availableAt);
        } catch (RuntimeException unreachable) {
            // Abgebrochen oder platform-api weg - im zweiten Fall gibt die Lease den Job wieder frei.
            LOG.warn("Warten auf Kontingent fuer Job {} nicht meldbar: {}", job.jobId(), unreachable.getMessage());
        }
    }

    /** Fertig heisst nicht vollstaendig: was fehlt, steht in der Meldung (und in der Quellnotiz). */
    static String summary(de.tstieh.stoneintelligence.stoneai.pipeline.IngestReport report) {
        var done = report.notesWritten() + " Notizen geschrieben";
        var gaps = report.gaps();
        if (gaps.isEmpty()) {
            return done;
        }
        var listed = String.join("; ", gaps.size() > 5 ? gaps.subList(0, 5) : gaps) + (gaps.size() > 5 ? " …" : "");
        return done + " – nicht verarbeitet: " + listed;
    }

    /**
     * Forwards the pipeline's stage progress to platform-api, without a network call for every
     * one of the potentially hundreds of chunks of a long document.
     */
    private ProgressSink throttled(ClaimedJob job, AtomicBoolean cancelled) {
        AtomicLong lastSentAt = new AtomicLong(Long.MIN_VALUE / 2);
        return (message, percent) -> {
            if (cancelled.get()) {
                throw new Cancelled();
            }
            long now = System.currentTimeMillis();
            if (percent < 100 && now - lastSentAt.get() < progressInterval.toMillis()) {
                return;
            }
            lastSentAt.set(now);
            try {
                platform.progress(job.jobId(), message, percent);
            } catch (JobGoneException gone) {
                cancelled.set(true);
                throw new Cancelled();
            } catch (RuntimeException ignored) {
                // Naechste Meldung versucht es erneut; die Lease haelt der Heartbeat ohnehin am Leben.
            }
        };
    }

    private void beat(ClaimedJob job, AtomicBoolean cancelled) {
        try {
            platform.progress(job.jobId(), "Wird verarbeitet", null);
        } catch (JobGoneException gone) {
            cancelled.set(true);
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
