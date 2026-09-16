package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    public NoteController(NoteRepository notes, SyncRelayService relay) {
        this.notes = notes;
        this.relay = relay;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/notes")
    public NoteResponse create(
        @PathVariable String vaultId,
        @RequestBody CreateNoteRequest request,
        @RequestHeader("X-Actor") String actor
    ) {
        var note = notes.create(VaultId.of(vaultId), request.path(), NoteLevel.of(request.noteLevel()), actor);
        return NoteResponse.from(note);
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
        return TombstoneResponse.from(tombstone);
    }

    public record CreateNoteRequest(String path, int noteLevel) {
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
}
