package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.audit.AuditService;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ID-first (Plan.md Abschnitt 3, Fehlerklasse 5): jede Mutation adressiert die {@link NoteId},
 * nie den Pfad. {@code X-Actor} ist derselbe provisorische Platzhalter wie in
 * {@code TicketController} bis Phase 3 (s. {@code SecurityConfig}).
 */
@RestController
public class NoteController {

    private final NoteRepository notes;
    private final SyncRelayService relay;
    private final AuditService audit;

    public NoteController(NoteRepository notes, SyncRelayService relay, AuditService audit) {
        this.notes = notes;
        this.relay = relay;
        this.audit = audit;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/notes")
    public NoteResponse create(
        @PathVariable String vaultId,
        @RequestBody CreateNoteRequest request,
        @RequestHeader("X-Actor") String actor
    ) {
        var vId = VaultId.of(vaultId);
        var note = notes.create(vId, request.path(), NoteLevel.of(request.noteLevel()), actor);
        audit.record(vId, note.id(), actor, "note.created", java.util.Map.of("path", note.path()));
        return NoteResponse.from(note);
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/audit")
    public List<AuditEventResponse> auditTrail(@PathVariable String vaultId, @PathVariable String noteId) {
        return audit.listForNote(VaultId.of(vaultId), NoteId.of(noteId)).stream()
            .map(AuditEventResponse::from)
            .toList();
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    public ResponseEntity<NoteResponse> get(@PathVariable String vaultId, @PathVariable String noteId) {
        return notes.findById(NoteId.of(noteId))
            .map(NoteResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Fehlerklasse 2 (Plan.md Abschnitt 3): jede Antwort traegt eine ueber alle Seiten stabile
     * {@code epochId} und ein {@code complete}-Flag - der Client darf eine unvollstaendige
     * Antwort NIE als Grundlage fuer lokale Loeschungen verwenden.
     */
    @GetMapping("/api/v1/vaults/{vaultId}/notes")
    public ReconciliationResponse reconcile(
        @PathVariable String vaultId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "100") int pageSize
    ) {
        var page = notes.list(VaultId.of(vaultId), cursor, pageSize);
        return new ReconciliationResponse(
            page.epochId(), page.complete(), page.nextCursor().orElse(null),
            page.notes().stream().map(NoteResponse::from).toList());
    }

    @PatchMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    public NoteResponse rename(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        @RequestBody RenameNoteRequest request,
        @RequestHeader("X-Actor") String actor
    ) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var before = notes.findById(nId).map(Note::path).orElse(null);
        var note = notes.rename(vId, nId, request.path());
        audit.record(vId, nId, actor, "note.renamed", java.util.Map.of("from", String.valueOf(before), "to", note.path()));
        return NoteResponse.from(note);
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    public TombstoneResponse delete(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        @RequestHeader("X-Operation-Id") String operationId,
        @RequestHeader("X-Actor") String actor
    ) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var tombstone = notes.delete(vId, nId, operationId, actor);
        relay.onNoteDeleted(nId);
        audit.record(vId, nId, actor, "note.deleted", java.util.Map.of("operationId", operationId));
        return TombstoneResponse.from(tombstone);
    }

    public record CreateNoteRequest(String path, int noteLevel) {
    }

    public record RenameNoteRequest(String path) {
    }

    public record NoteResponse(String id, String vaultId, String path, int noteLevel, String createdBy, Instant createdAt) {
        static NoteResponse from(Note note) {
            return new NoteResponse(
                note.id().value().toString(), note.vaultId().value().toString(),
                note.path(), note.level().value(), note.createdBy(), note.createdAt());
        }
    }

    public record TombstoneResponse(String noteId, String operationId, long serverSequence, String deletedBy, Instant deletedAt) {
        static TombstoneResponse from(Tombstone tombstone) {
            return new TombstoneResponse(
                tombstone.noteId().value().toString(), tombstone.operationId(),
                tombstone.serverSequence(), tombstone.deletedBy(), tombstone.deletedAt());
        }
    }

    public record ReconciliationResponse(java.util.UUID epochId, boolean complete, String nextCursor, List<NoteResponse> notes) {
    }

    public record AuditEventResponse(String actor, String action, java.util.Map<String, Object> payload, Instant occurredAt) {
        static AuditEventResponse from(de.raindancer118.stoneintelligence.platform.audit.AuditEvent event) {
            return new AuditEventResponse(event.actor(), event.action(), event.payload(), event.occurredAt());
        }
    }
}
