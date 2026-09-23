package de.raindancer118.stoneintelligence.platform.ai;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;

/**
 * Hochgeladene Dokumente als KI-Jobs (ADR 0008): prueft beim Hochladen, ob der gewaehlte Dienst
 * das Level des Dokuments verarbeiten darf und ob die Pipeline das Dokument lesen kann, und
 * steuert, wie der Worker Jobs holt, meldet und abschliesst. Berechtigungen prueft der Controller.
 */
public class AiJobService {

    static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    static final int MAX_FILES_PER_UPLOAD = 10;
    static final int MAX_OPEN_JOBS_PER_VAULT = 20;
    static final int MAX_ATTEMPTS = 3;
    static final Duration LEASE = Duration.ofMinutes(10);
    private static final Duration FIRST_RETRY_PAUSE = Duration.ofMinutes(1);
    private static final int MAX_FILE_NAME_LENGTH = 200;

    /** Legt das Change-Set fuer einen Job an; liefert dessen Id. */
    @FunctionalInterface
    public interface ChangeSetStarter {
        UUID start(VaultId vaultId, AiService service, String requestedBy, String label);
    }

    public record Upload(String fileName, String contentType, byte[] content) { }

    private final AiJobRepository jobs;
    private final Supplier<AiServiceDirectory> services;
    private final ChangeSetStarter changeSets;
    private final Supplier<Instant> clock;

    public AiJobService(AiJobRepository jobs, Supplier<AiServiceDirectory> services, ChangeSetStarter changeSets,
                        Supplier<Instant> clock) {
        this.jobs = jobs;
        this.services = services;
        this.changeSets = changeSets;
        this.clock = clock;
    }

    public List<AiJob> upload(VaultId vaultId, String actor, String serviceId, int level, List<Upload> uploads) {
        var service = services.get().find(String.valueOf(serviceId))
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + serviceId + " ist nicht eingerichtet"));
        if (level < NoteLevel.MIN || level >= NoteLevel.NO_SYNC || !service.mayProcess(NoteLevel.of(level))) {
            throw new AiWriteRefusedException(service.name() + " darf Dokumente mit Level " + level + " nicht verarbeiten");
        }
        if (uploads.isEmpty() || uploads.size() > MAX_FILES_PER_UPLOAD) {
            throw new AiWriteRefusedException("1 bis " + MAX_FILES_PER_UPLOAD + " Dateien je Upload");
        }
        if (jobs.countOpen(vaultId) + uploads.size() > MAX_OPEN_JOBS_PER_VAULT) {
            throw new AiWriteRefusedException("In diesem Vault warten schon zu viele Dokumente - bitte später erneut versuchen");
        }
        var accepted = uploads.stream().map(upload -> new NewAiJob(vaultId, service.id(), actor, safeFileName(upload.fileName()),
            contentTypeOf(upload), level, upload.content(), MAX_ATTEMPTS)).toList();
        return accepted.stream().map(job -> jobs.create(job, clock.get())).toList();
    }

    public List<AiJob> list(VaultId vaultId, int limit) {
        return jobs.list(vaultId, limit);
    }

    public boolean cancel(VaultId vaultId, UUID jobId) {
        return jobs.cancel(vaultId, jobId, clock.get());
    }

    /**
     * Naechster Job fuer den Worker, mit Change-Set (beim ersten Mal angelegt, beim Wiederanlauf
     * wiederverwendet). Jobs eines nicht mehr eingerichteten Dienstes schlagen fehl.
     */
    public Optional<AiJob> claim() {
        for (var job = jobs.claim(clock.get(), LEASE); job.isPresent(); job = jobs.claim(clock.get(), LEASE)) {
            var claimed = job.get();
            var service = services.get().find(claimed.service());
            if (service.isEmpty() || !service.get().mayProcess(NoteLevel.of(claimed.level()))) {
                jobs.finish(claimed.id(), AiJob.Status.FAILED, "Der KI-Dienst ist nicht mehr für dieses Dokument eingerichtet",
                    clock.get());
                continue;
            }
            if (claimed.changeSetId() != null) {
                return Optional.of(claimed);
            }
            var changeSetId = changeSets.start(claimed.vaultId(), service.get(), claimed.requestedBy(), claimed.fileName());
            jobs.attachChangeSet(claimed.id(), changeSetId);
            return jobs.findById(claimed.id());
        }
        return Optional.empty();
    }

    public AiJob running(UUID jobId) {
        return jobs.findById(jobId).filter(job -> job.status() == AiJob.Status.RUNNING)
            .orElseThrow(() -> new AiWriteRefusedException("Dieser Job läuft nicht (mehr)"));
    }

    public byte[] document(UUID jobId) {
        running(jobId);
        return jobs.content(jobId).orElseThrow(() -> new AiWriteRefusedException("Das Dokument ist nicht mehr vorhanden"));
    }

    public void progress(UUID jobId, String message, Integer percent) {
        if (percent != null && (percent < 0 || percent > 100) || message != null && message.length() > 300) {
            throw new AiWriteRefusedException("percent 0..100, message höchstens 300 Zeichen");
        }
        if (!jobs.progress(jobId, message, percent, clock.get().plus(LEASE))) {
            throw new AiWriteRefusedException("Dieser Job läuft nicht (mehr)");
        }
    }

    public void complete(UUID jobId) {
        if (!jobs.finish(jobId, AiJob.Status.SUCCEEDED, null, clock.get())) {
            throw new AiWriteRefusedException("Dieser Job läuft nicht (mehr)");
        }
    }

    /** Voruebergehende Probleme mit wachsenden Pausen erneut versuchen (1, 2, 4 … Minuten), sonst endgueltig. */
    public void fail(UUID jobId, String error, boolean retryable) {
        var job = running(jobId);
        var message = error == null || error.isBlank() ? "Unbekannter Fehler" : error.strip();
        message = message.length() > 1000 ? message.substring(0, 1000) : message;
        var done = retryable && job.attempts() < job.maxAttempts()
            ? jobs.retryLater(jobId, message, clock.get().plus(FIRST_RETRY_PAUSE.multipliedBy(1L << (job.attempts() - 1))))
            : jobs.finish(jobId, AiJob.Status.FAILED, message, clock.get());
        if (!done) {
            throw new AiWriteRefusedException("Dieser Job läuft nicht (mehr)");
        }
    }

    public int purgeCreatedBefore(Instant cutoff) {
        return jobs.purgeCreatedBefore(cutoff);
    }

    /** Nur der Dateiname, ohne Pfad und Steuerzeichen - er erscheint im Vault und im Dashboard. */
    static String safeFileName(String fileName) {
        var name = fileName == null ? "" : fileName;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.codePoints().filter(c -> !Character.isISOControl(c))
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "Dokument";
        }
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
    }

    /** Am Inhalt erkannt: PDF oder UTF-8-Text (Markdown/Klartext). Alles andere kann die Pipeline nicht lesen. */
    static String contentTypeOf(Upload upload) {
        var content = upload.content();
        if (content == null || content.length == 0) {
            throw new AiWriteRefusedException(upload.fileName() + " ist leer");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new AiWriteRefusedException(upload.fileName() + " ist größer als " + MAX_FILE_BYTES / (1024 * 1024) + " MB");
        }
        if (content.length >= 5 && new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            return "application/pdf";
        }
        var name = String.valueOf(upload.fileName()).toLowerCase(Locale.ROOT);
        if ((name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")) && isText(content)) {
            return name.endsWith(".txt") ? "text/plain" : "text/markdown";
        }
        throw new AiWriteRefusedException(upload.fileName() + ": nur PDF-, Markdown- und Textdateien werden unterstützt");
    }

    private static boolean isText(byte[] content) {
        for (var b : content) {
            if (b == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException notUtf8) {
            return false;
        }
    }
}
