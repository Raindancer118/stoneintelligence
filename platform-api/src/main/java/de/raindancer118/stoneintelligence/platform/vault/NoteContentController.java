package de.raindancer118.stoneintelligence.platform.vault;

import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.audit.AuditService;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.sync.relay.SnapshotStore;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.raindancer118.stoneintelligence.platform.sync.relay.UpdateRecord;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Browser-Editor: opake Yjs-Updates, ausdrueckliches Speichern mit Versionspruefung. */
@RestController
@RequestMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/content")
public class NoteContentController {
    private final NoteRepository notes;
    private final SnapshotStore snapshots;
    private final SyncRelayService relay;
    private final VaultAccessGuard access;
    private final AuditService audit;
    private final VaultAnnouncementService announcements;

    public NoteContentController(NoteRepository notes, SnapshotStore snapshots, SyncRelayService relay,
                                 VaultAccessGuard access, AuditService audit, VaultAnnouncementService announcements) {
        this.announcements = announcements;
        this.notes = notes;
        this.snapshots = snapshots;
        this.relay = relay;
        this.access = access;
        this.audit = audit;
    }

    private Note authorizedNote(String vaultId, String noteId, Authentication auth, Permission permission) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        access.require(vId, auth.getName(), Permission.READ);
        var note = notes.findById(vId, nId).orElseThrow(() -> new NoteNotFoundException(vId, nId));
        access.require(vId, auth.getName(), permission, note.path());
        if (note.level().value() == 101) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Encrypted notes require the Obsidian client");
        }
        return note;
    }

    @GetMapping
    public ContentResponse read(@PathVariable String vaultId, @PathVariable String noteId, Authentication auth) {
        var note = authorizedNote(vaultId, noteId, auth, Permission.READ);
        var updates = snapshots.listSince(note.id(), 0);
        if (updates.stream().anyMatch(UpdateRecord::ciphertext)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Encrypted content cannot be opened in this editor");
        }
        return new ContentResponse(updates.isEmpty() ? 0 : updates.getLast().serverSequence(),
            updates.stream().map(UpdateRecord::payload).toList());
    }

    @PostMapping
    public SavedResponse save(@PathVariable String vaultId, @PathVariable String noteId,
                              @RequestBody SaveRequest request, Authentication auth) {
        var note = authorizedNote(vaultId, noteId, auth, Permission.WRITE);
        if (request.expectedRevision() == null || request.expectedRevision() < 0 || request.update() == null
                || request.update().length < 2 || request.update().length > 2 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid update or revision");
        }
        var saved = relay.saveIfCurrent(note.id(), request.expectedRevision(), request.update())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Note changed; reload before saving"));
        audit.record(note.vaultId(), note.id(), auth.getName(), "note.content-updated",
            java.util.Map.of("revision", saved.serverSequence()));
        announcements.announceNoteUpdated(note.vaultId(), note.id(), () -> java.util.Optional.of(note.path()));
        return new SavedResponse(saved.serverSequence());
    }

    public record ContentResponse(long revision, List<byte[]> updates) { }
    public record SaveRequest(Long expectedRevision, byte[] update) { }
    public record SavedResponse(long revision) { }
}
