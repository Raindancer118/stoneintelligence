package de.tstieh.stoneintelligence.platform.vault.feed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Feed im Speicher fuer einen Vault: jede Meldung bekommt die naechste "Transaktion". */
public final class FakeChangeFeed implements ChangeFeed {

    private record Change(long tx, NoteId noteId) { }
    private record Folder(long tx, String path, boolean removed) { }
    private record Access(long tx, AccessEvent event) { }

    private long tx = 1;
    private final List<Change> changes = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();
    private final List<Access> access = new ArrayList<>();

    public synchronized void changed(NoteId noteId) {
        changes.add(new Change(tx++, noteId));
    }

    public synchronized void folderChanged(String path) {
        folders.add(new Folder(tx++, path, false));
    }

    public synchronized void folderRemoved(String path) {
        folders.add(new Folder(tx++, path, true));
    }

    public synchronized void access(AccessEvent event) {
        access.add(new Access(tx++, event));
    }

    @Override
    public synchronized long now() {
        return tx;
    }

    @Override
    public synchronized Batch read(VaultId vaultId, FeedCursor cursor, int limit) {
        var since = cursor.since();
        var continuation = cursor.continuation() != null ? cursor.continuation() : new FeedCursor.Continuation(now(), 0, new UUID(0, 0));
        var upto = continuation.upto();
        var latest = new LinkedHashMap<NoteId, Long>();
        changes.stream().filter(c -> c.tx() >= since && c.tx() < upto).forEach(c -> latest.merge(c.noteId(), c.tx(), Math::max));
        Comparator<java.util.Map.Entry<NoteId, Long>> order = Comparator.<java.util.Map.Entry<NoteId, Long>>comparingLong(java.util.Map.Entry::getValue)
            .thenComparing(e -> e.getKey().value());
        var rows = latest.entrySet().stream().sorted(order)
            .filter(e -> e.getValue() > continuation.lastTx()
                || (e.getValue() == continuation.lastTx() && e.getKey().value().compareTo(continuation.lastId()) > 0))
            .toList();
        var page = rows.stream().limit(limit).toList();
        var ids = page.stream().map(java.util.Map.Entry::getKey).toList();
        if (rows.size() > limit) {
            var last = page.getLast();
            return new Batch(ids, true, new FeedCursor(since, new FeedCursor.Continuation(upto, last.getValue(), last.getKey().value())),
                List.of(), List.of(), List.of());
        }
        var inRange = folders.stream().filter(f -> f.tx() >= since && f.tx() < upto).toList();
        return new Batch(ids, false, FeedCursor.at(upto),
            inRange.stream().filter(f -> !f.removed()).map(Folder::path).distinct().toList(),
            inRange.stream().filter(Folder::removed).map(Folder::path).distinct().toList(),
            access.stream().filter(a -> a.tx() >= since && a.tx() < upto).map(Access::event).toList());
    }
}
