package de.tstieh.stoneintelligence.platform.ai;

import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.NoteNotFoundException;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Der Schreibweg des KI-Workers (ADR 0008), nur mit Worker-Token erreichbar. Die KI handelt
 * hoechstens mit den Rechten der Person, die die Verarbeitung ausgeloest hat ({@code requestedBy}
 * des Change-Sets), und nur auf den Levels ihres Dienstes.
 */
@RestController
@RequestMapping("/internal/ai")
public class AiInternalController {

    static final int MAX_TEXT_LENGTH = 1_000_000;

    private final AiWriteService ai;
    private final AiServiceDirectory services;
    private final VaultAccessGuard access;
    private final NoteRepository notes;
    private final AiJobService jobs;
    private final AiCapacityBoard capacity;

    public AiInternalController(@Lazy AiWriteService ai, AiServiceDirectory services, VaultAccessGuard access, NoteRepository notes,
                                AiJobService jobs, AiCapacityBoard capacity) {
        this.jobs = jobs;
        this.capacity = capacity;
        this.ai = ai;
        this.services = services;
        this.access = access;
        this.notes = notes;
    }

    @GetMapping("/services")
    public List<AiServiceResponse> services() {
        return services.all().stream().map(AiServiceResponse::from).toList();
    }

    /** Der Worker meldet, wie viel Kontingent die Anbieter hinter einem Dienst noch haben. */
    @PutMapping("/services/{serviceId}/capacity")
    public AiCapacityBoard.ServiceCapacity reportCapacity(@PathVariable String serviceId, @RequestBody CapacityReport report) {
        capacity.report(serviceId, report.providers());
        return capacity.of(serviceId);
    }

    @PostMapping("/vaults/{vaultId}/change-sets")
    public ChangeSetCreated startChangeSet(@PathVariable String vaultId, @RequestBody StartChangeSetRequest request) {
        var vId = VaultId.of(vaultId);
        if (request.requestedBy() == null || request.label() == null || request.label().isBlank() || request.label().length() > 300) {
            throw new AiWriteRefusedException("requestedBy und label (1..300 Zeichen) sind Pflicht");
        }
        access.require(vId, request.requestedBy(), Permission.CREATE);
        var service = services.find(String.valueOf(request.service()))
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + request.service() + " ist nicht eingerichtet"));
        return new ChangeSetCreated(ai.startChangeSet(vId, service, request.requestedBy(), request.label().strip()).id());
    }

    @PostMapping("/vaults/{vaultId}/change-sets/{changeSetId}/notes")
    public NoteRef createNote(@PathVariable String vaultId, @PathVariable UUID changeSetId, @RequestBody CreateNoteRequest request) {
        var vId = VaultId.of(vaultId);
        var changeSet = changeSet(vId, changeSetId);
        access.require(vId, changeSet.requestedBy(), Permission.CREATE, String.valueOf(request.path()));
        if (request.level() == null) {
            throw new AiWriteRefusedException("level ist Pflicht");
        }
        var written = ai.createNote(vId, changeSetId, request.path(), text(request.text()), NoteLevel.of(request.level()));
        return new NoteRef(written.noteId().value().toString(), written.path());
    }

    /** Das gelesene Original als Datei im Vault (Rohdaten im Body, Pfad und Level als Parameter). */
    @PostMapping("/vaults/{vaultId}/change-sets/{changeSetId}/files")
    public NoteRef storeFile(@PathVariable String vaultId, @PathVariable UUID changeSetId,
                             @org.springframework.web.bind.annotation.RequestParam String path,
                             @org.springframework.web.bind.annotation.RequestParam int level,
                             @org.springframework.web.bind.annotation.RequestHeader(value = "Content-Type", required = false) String contentType,
                             @RequestBody byte[] content) {
        var vId = VaultId.of(vaultId);
        var changeSet = changeSet(vId, changeSetId);
        access.require(vId, changeSet.requestedBy(), Permission.CREATE, path);
        var written = ai.storeFile(vId, changeSetId, path, content,
            contentType == null ? "application/octet-stream" : contentType, NoteLevel.of(level));
        return new NoteRef(written.noteId().value().toString(), written.path());
    }

    @PutMapping("/vaults/{vaultId}/change-sets/{changeSetId}/notes/{noteId}")
    public NoteRef updateNote(@PathVariable String vaultId, @PathVariable UUID changeSetId, @PathVariable String noteId,
                                  @RequestBody UpdateNoteRequest request) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var changeSet = changeSet(vId, changeSetId);
        var note = notes.findById(vId, nId).orElseThrow(() -> new NoteNotFoundException(vId, nId));
        access.require(vId, changeSet.requestedBy(), Permission.WRITE, note.path());
        ai.updateNote(vId, changeSetId, nId, text(request.text()));
        return new NoteRef(noteId, note.path());
    }

    @GetMapping("/vaults/{vaultId}/change-sets/{changeSetId}/notes/{noteId}")
    public NoteText readNote(@PathVariable String vaultId, @PathVariable UUID changeSetId, @PathVariable String noteId) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var changeSet = changeSet(vId, changeSetId);
        var note = notes.findById(vId, nId).orElseThrow(() -> new NoteNotFoundException(vId, nId));
        access.require(vId, changeSet.requestedBy(), Permission.READ, note.path());
        var service = services.find(changeSet.service())
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + changeSet.service() + " ist nicht (mehr) eingerichtet"));
        return new NoteText(noteId, note.path(), ai.readText(vId, nId, service));
    }

    /**
     * Bestehende Notizen, die die KI fuer Verlinkung und Konsolidierung kennen darf: fuer
     * {@code requestedBy} lesbar und auf einem Level, das der Dienst verarbeiten darf.
     */
    @GetMapping("/vaults/{vaultId}/change-sets/{changeSetId}/notes")
    public List<ListedNote> listNotes(@PathVariable String vaultId, @PathVariable UUID changeSetId) {
        var vId = VaultId.of(vaultId);
        var changeSet = changeSet(vId, changeSetId);
        access.require(vId, changeSet.requestedBy(), Permission.READ);
        var service = services.find(changeSet.service())
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + changeSet.service() + " ist nicht (mehr) eingerichtet"));
        var listed = new java.util.ArrayList<ListedNote>();
        String cursor = null;
        do {
            var page = notes.list(vId, cursor, 500);
            access.readableNotes(vId, changeSet.requestedBy(), page.notes()).stream()
                .filter(note -> ai.mayProcess(service, note.level()))
                .map(note -> new ListedNote(note.id().value().toString(), note.path(), note.level().value(), note.createdBy(), note.kind().name()))
                .forEach(listed::add);
            cursor = page.complete() ? null : page.nextCursor().orElse(null);
        } while (cursor != null);
        return listed;
    }

    @PostMapping("/jobs/claim")
    public org.springframework.http.ResponseEntity<ClaimedJob> claim() {
        return jobs.claim().map(ClaimedJob::from).map(org.springframework.http.ResponseEntity::ok)
            .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/jobs/{jobId}/document")
    public org.springframework.http.ResponseEntity<byte[]> document(@PathVariable UUID jobId) {
        var job = jobs.running(jobId);
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(job.contentType()))
            .body(jobs.document(jobId));
    }

    @PostMapping("/jobs/{jobId}/progress")
    public JobAck progress(@PathVariable UUID jobId, @RequestBody ProgressRequest request) {
        jobs.progress(jobId, request.message(), request.percent());
        return new JobAck(jobId);
    }

    @PostMapping("/jobs/{jobId}/complete")
    public JobAck complete(@PathVariable UUID jobId) {
        jobs.complete(jobId);
        return new JobAck(jobId);
    }

    @PostMapping("/jobs/{jobId}/fail")
    public JobAck fail(@PathVariable UUID jobId, @RequestBody FailRequest request) {
        jobs.fail(jobId, request.error(), Boolean.TRUE.equals(request.retryable()));
        return new JobAck(jobId);
    }

    @PostMapping("/jobs/{jobId}/wait-for-capacity")
    public JobAck waitForCapacity(@PathVariable UUID jobId, @RequestBody WaitForCapacityRequest request) {
        jobs.waitForCapacity(jobId, request.error(), request.availableAt());
        return new JobAck(jobId);
    }

    private AiChangeSet changeSet(VaultId vaultId, UUID changeSetId) {
        return ai.changeSet(vaultId, changeSetId).orElseThrow(() -> new AiWriteRefusedException("KI-Änderung nicht gefunden"));
    }

    private static String text(String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) {
            throw new AiWriteRefusedException("text fehlt oder ist länger als " + MAX_TEXT_LENGTH + " Zeichen");
        }
        return text;
    }

    /** Abgebrochen oder beendet: der Worker hoert daran auf, statt weiterzurechnen. */
    @org.springframework.web.bind.annotation.ExceptionHandler(AiJobGoneException.class)
    public org.springframework.http.ProblemDetail gone(AiJobGoneException gone) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.GONE, gone.getMessage());
    }

    /** Die Texte sind fuer Menschen geschrieben und verraten nichts Internes - direkt anzeigen lassen. */
    @org.springframework.web.bind.annotation.ExceptionHandler(AiWriteRefusedException.class)
    public org.springframework.http.ProblemDetail refused(AiWriteRefusedException refused) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage());
    }

    public record StartChangeSetRequest(String service, String requestedBy, String label) { }
    public record ChangeSetCreated(UUID id) { }
    public record CreateNoteRequest(String path, String text, Integer level) { }
    public record UpdateNoteRequest(String text) { }
    public record NoteRef(String noteId, String path) { }
    public record ListedNote(String noteId, String path, int level, String createdBy, String kind) { }
    public record ProgressRequest(String message, Integer percent) { }
    public record FailRequest(String error, Boolean retryable) { }
    public record WaitForCapacityRequest(String error, java.time.Instant availableAt) { }
    public record CapacityReport(List<AiCapacityBoard.ProviderReport> providers) { }
    public record JobAck(UUID jobId) { }

    /** Was der Worker fuer einen Job braucht - das Dokument holt er gesondert. */
    public record ClaimedJob(UUID jobId, String vaultId, String service, String requestedBy, String fileName, String contentType,
                             long size, int level, UUID changeSetId, int attempt) {
        static ClaimedJob from(AiJob job) {
            return new ClaimedJob(job.id(), job.vaultId().value().toString(), job.service(), job.requestedBy(), job.fileName(),
                job.contentType(), job.size(), job.level(), job.changeSetId(), job.attempts());
        }
    }
    public record NoteText(String noteId, String path, String text) { }
}
