package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.audit.AuditService;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
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
 * nie den Pfad. Der Actor kommt seit Phase 3 (ADR 0006 Punkt 6, {@code SecurityConfig}) aus dem
 * authentifizierten OIDC-Principal ({@code Authentication#getName()}, gemappt auf
 * {@code preferred_username}), nicht mehr aus einem client-behaupteten Header.
 */
@RestController
public class NoteController {

    private final NoteRepository notes;
    private final SyncRelayService relay;
    private final AuditService audit;
    private final VaultAccessGuard access;

    public NoteController(NoteRepository notes, SyncRelayService relay, AuditService audit, VaultAccessGuard access) {
        this.notes = notes;
        this.relay = relay;
        this.audit = audit;
        this.access = access;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/notes")
    @Transactional
    public NoteResponse create(
        @PathVariable String vaultId,
        @RequestBody CreateNoteRequest request,
        Authentication authentication
    ) {
        var actor = authentication.getName();
        var vId = VaultId.of(vaultId);
        access.require(vId, actor, Permission.CREATE, request.path());
        var note = notes.create(vId, request.path(), NoteLevel.of(request.noteLevel()), actor);
        audit.record(vId, note.id(), actor, "note.created", java.util.Map.of("path", note.path()));
        return NoteResponse.from(note);
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/audit")
    public List<AuditEventResponse> auditTrail(
        @PathVariable String vaultId, @PathVariable String noteId, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.READ);
        return audit.listForNote(vId, NoteId.of(noteId)).stream()
            .map(AuditEventResponse::from)
            .toList();
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    public ResponseEntity<NoteResponse> get(
        @PathVariable String vaultId, @PathVariable String noteId, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.READ);
        return notes.findById(vId, NoteId.of(noteId))
            .map(NoteResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Fehlerklasse 2 (Plan.md Abschnitt 3): jede Antwort traegt eine ueber alle Seiten stabile
     * {@code epochId} und ein {@code complete}-Flag - der Client darf eine unvollstaendige
     * Antwort NIE als Grundlage fuer lokale Loeschungen verwenden.
     *
     * <p>Die Berechtigungspruefung greift hier nur auf Vault-Ebene (READ) - eine Filterung
     * einzelner Notes nach {@code PathRules} innerhalb der Seite ist NICHT umgesetzt (waere
     * zusaetzlich noetig, sobald einzelne Ordner fuer ein Subject per Regel gesperrt werden
     * sollen, s. {@code VaultAccessGuard}).
     */
    @GetMapping("/api/v1/vaults/{vaultId}/notes")
    public ReconciliationResponse reconcile(
        @PathVariable String vaultId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "100") int pageSize,
        Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.READ);
        var page = notes.list(vId, cursor, pageSize);
        return new ReconciliationResponse(
            page.epochId(), page.complete(), page.nextCursor().orElse(null),
            page.notes().stream().map(NoteResponse::from).toList());
    }

    @PatchMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    @Transactional
    public NoteResponse rename(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        @RequestBody RenameNoteRequest request,
        Authentication authentication
    ) {
        var actor = authentication.getName();
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var before = notes.findById(vId, nId).map(Note::path).orElse(null);
        access.require(vId, actor, Permission.WRITE, before != null ? before : request.path());
        var note = notes.rename(vId, nId, request.path());
        audit.record(vId, nId, actor, "note.renamed", java.util.Map.of("from", String.valueOf(before), "to", note.path()));
        return NoteResponse.from(note);
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    @Transactional
    public TombstoneResponse delete(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        @RequestHeader("X-Operation-Id") String operationId,
        Authentication authentication
    ) {
        var actor = authentication.getName();
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var path = notes.findById(vId, nId).map(Note::path).orElse(null);
        if (path != null) {
            access.require(vId, actor, Permission.DELETE, path);
        } else {
            access.require(vId, actor, Permission.DELETE);
        }
        var tombstone = notes.delete(vId, nId, operationId, actor);
        audit.record(vId, nId, actor, "note.deleted", java.util.Map.of("operationId", operationId));
        // Erst NACH den DB-Schreibvorgaengen ankuendigen, dass die Note weg ist - sonst wuerde
        // ein Client benachrichtigt, bevor der Audit-Eintrag (oder gar die Loeschung selbst bei
        // einem spaeteren Rollback) tatsaechlich feststeht.
        relay.onNoteDeleted(nId);
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
