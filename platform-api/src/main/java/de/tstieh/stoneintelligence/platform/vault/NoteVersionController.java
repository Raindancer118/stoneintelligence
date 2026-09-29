package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.audit.AuditRecorder;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.SnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.tstieh.stoneintelligence.platform.sync.relay.UpdateInfo;
import de.tstieh.stoneintelligence.platform.sync.relay.UpdateRecord;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Versionsverlauf einer Notiz: jede Version laesst sich aus dem Update-Log nachbauen, das der
 * Server ohnehin vollstaendig aufbewahrt. Wiederherstellen haengt den alten Text als neue
 * Aenderung an - der Verlauf dazwischen bleibt erhalten, offene Editoren bekommen es live.
 */
@RestController
@RequestMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/versions")
public class NoteVersionController {

    static final int MAX_LIMIT = 500;
    private static final int WRITE_ATTEMPTS = 5;

    private final NoteRepository notes;
    private final SnapshotStore snapshots;
    private final SyncRelayService relay;
    private final ObjectProvider<YjsBridge> yjsProvider;
    private final VaultAccessGuard access;
    private final AuditRecorder audit;
    private final VaultAnnouncementService announcements;

    public NoteVersionController(NoteRepository notes, SnapshotStore snapshots, SyncRelayService relay, ObjectProvider<YjsBridge> yjs,
                                 VaultAccessGuard access, AuditRecorder audit, VaultAnnouncementService announcements) {
        this.notes = notes;
        this.snapshots = snapshots;
        this.relay = relay;
        this.yjsProvider = yjs;
        this.access = access;
        this.audit = audit;
        this.announcements = announcements;
    }

    @GetMapping
    public VersionList list(@PathVariable String vaultId, @PathVariable String noteId,
                            @RequestParam(defaultValue = "100") int limit, Authentication auth) {
        var note = authorizedNote(vaultId, noteId, auth, Permission.READ);
        var log = plainLog(note);
        var versions = NoteVersions.group(log);
        return new VersionList(revisionOf(log), versions.stream().limit(Math.min(Math.max(limit, 1), MAX_LIMIT)).toList(),
            versions.size());
    }

    @GetMapping("/{revision}")
    public VersionText show(@PathVariable String vaultId, @PathVariable String noteId, @PathVariable long revision,
                            Authentication auth) {
        var note = authorizedNote(vaultId, noteId, auth, Permission.READ);
        var log = plainLog(note);
        var at = existing(log, revision);
        var all = payloads(snapshots.listSince(note.id(), 0));
        return new VersionText(revision, at.createdAt(), at.actor(), textAt(note, revision), yjs().textOf(all), revisionOf(log));
    }

    @PostMapping("/{revision}/restore")
    public Restored restore(@PathVariable String vaultId, @PathVariable String noteId, @PathVariable long revision,
                            Authentication auth) {
        var note = authorizedNote(vaultId, noteId, auth, Permission.WRITE);
        existing(plainLog(note), revision);
        var text = textAt(note, revision);
        for (var attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            var history = snapshots.listSince(note.id(), 0);
            var current = history.isEmpty() ? 0 : history.getLast().serverSequence();
            var update = yjs().change(payloads(history), text);
            if (update.isEmpty()) {
                return new Restored(current, false);
            }
            var saved = relay.saveIfCurrent(note.id(), current, update.get(), auth.getName());
            if (saved.isPresent()) {
                var now = Instant.now();
                audit.record(note.vaultId(), note.id(), auth.getName(), "note.version-restored",
                    Map.of("restoredRevision", revision, "revision", saved.get().serverSequence(), "path", note.path()));
                notes.markEdited(note.vaultId(), note.id(), auth.getName(), now);
                announcements.announceNoteUpdated(note.vaultId(), note.id(), () -> Optional.of(note.path()));
                return new Restored(saved.get().serverSequence(), true);
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Note changes too often right now - try again");
    }

    /** Das Yjs-Bundle laedt erst beim ersten Bedarf (~2 s), nicht beim Start. */
    private YjsBridge yjs() {
        return yjsProvider.getObject();
    }

    private Note authorizedNote(String vaultId, String noteId, Authentication auth, Permission permission) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        access.requireMember(vId, auth.getName());
        var note = notes.findById(vId, nId).orElseThrow(() -> new NoteNotFoundException(vId, nId));
        access.require(vId, auth.getName(), permission, note.path());
        if (note.isFile()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Files keep no older versions");
        }
        if (note.level().value() == 101) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Encrypted notes require the Obsidian client");
        }
        return note;
    }

    private List<UpdateInfo> plainLog(Note note) {
        var log = snapshots.log(note.id());
        if (log.stream().anyMatch(UpdateInfo::ciphertext)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Encrypted content has no readable versions");
        }
        return log;
    }

    private static UpdateInfo existing(List<UpdateInfo> log, long revision) {
        return log.stream().filter(update -> update.serverSequence() == revision).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such version"));
    }

    private String textAt(Note note, long revision) {
        return yjs().textOf(payloads(snapshots.listUpTo(note.id(), revision)));
    }

    private static long revisionOf(List<UpdateInfo> log) {
        return log.isEmpty() ? 0 : log.getLast().serverSequence();
    }

    private static List<byte[]> payloads(List<UpdateRecord> records) {
        return records.stream().map(UpdateRecord::payload).toList();
    }

    /** {@code total}: alle Versionen, auch die ueber {@code limit} hinaus. */
    public record VersionList(long currentRevision, List<NoteVersion> versions, int total) { }

    public record VersionText(long revision, Instant at, String actor, String text, String current, long currentRevision) { }

    public record Restored(long revision, boolean changed) { }
}
