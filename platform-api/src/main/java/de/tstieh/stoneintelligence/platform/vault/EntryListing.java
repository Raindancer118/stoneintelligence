package de.tstieh.stoneintelligence.platform.vault;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.files.FileVersionRepository;
import de.tstieh.stoneintelligence.platform.sync.relay.SnapshotStore;
import org.springframework.stereotype.Component;

/** Eintraege so, wie Liste, Suche und Aenderungs-Feed sie ausliefern: mit Revision und den Rechten des Aufrufers. */
@Component
public class EntryListing {

    private final SnapshotStore snapshots;
    private final FileVersionRepository fileVersions;
    private final VaultAccessGuard access;

    public EntryListing(SnapshotStore snapshots, FileVersionRepository fileVersions, VaultAccessGuard access) {
        this.snapshots = snapshots;
        this.fileVersions = fileVersions;
        this.access = access;
    }

    /** {@code readable}: schon auf Leserecht gefiltert. */
    public List<NoteController.ListedNoteResponse> listed(VaultId vaultId, String actor, List<Note> readable) {
        var revisions = snapshots.latestRevisions(readable.stream().filter(note -> !note.isFile()).map(Note::id).toList());
        var files = fileVersions.current(readable.stream().filter(Note::isFile).map(Note::id).toList());
        var entryAccess = access.entryAccess(vaultId, actor, readable);
        return readable.stream().map(note -> (note.isFile()
            ? NoteController.ListedNoteResponse.fromFile(note, files.get(note.id()))
            : NoteController.ListedNoteResponse.from(note, revisions.getOrDefault(note.id(), 0L))).with(entryAccess.get(note.id()))).toList();
    }
}
