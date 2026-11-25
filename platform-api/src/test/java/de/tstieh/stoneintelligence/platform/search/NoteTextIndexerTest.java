package de.tstieh.stoneintelligence.platform.search;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.sync.relay.FakeSnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoteTextIndexerTest {

    private static YjsBridge bridge;

    @BeforeAll
    static void load() {
        bridge = YjsBridge.load();
    }

    @AfterAll
    static void close() {
        bridge.close();
    }

    private final FakeSnapshotStore snapshots = new FakeSnapshotStore();
    private final FakeNoteTextIndex index = new FakeNoteTextIndex();
    private final NoteTextIndexer indexer = new NoteTextIndexer(index, snapshots, () -> bridge);
    private final VaultId vault = VaultId.newId();

    private NoteId noteWith(String... versions) {
        var note = NoteId.newId();
        var history = new java.util.ArrayList<byte[]>();
        for (var text : versions) {
            var update = bridge.change(history, text).orElseThrow();
            history.add(update);
            snapshots.append(note, update, false, "tom");
        }
        index.enqueue(note, vault);
        return note;
    }

    @Test
    void should_indexTheCurrentText_ofQueuedNotes() {
        var note = noteWith("Entwurf", "Lieferantenauswahl und Bestellmengen");

        assertThat(indexer.runOnce()).isEqualTo(1);

        assertThat(index.texts.get(note).text()).isEqualTo("Lieferantenauswahl und Bestellmengen");
        assertThat(index.texts.get(note).revision()).isEqualTo(2);
        assertThat(index.matching(vault, "bestell:*", 10)).containsExactly(note);
        assertThat(index.queue).isEmpty();
    }

    @Test
    void should_neverIndexEncryptedNotes_andForgetOldText() {
        var note = NoteId.newId();
        index.upsert(note, vault, 1, "frueher Klartext");
        snapshots.append(note, new byte[] {1, 2, 3}, true, "tom");
        index.enqueue(note, vault);

        indexer.runOnce();

        assertThat(index.texts).doesNotContainKey(note);
    }

    // Datenbank weg: voruebergehend, die Notiz kommt zurueck in die Warteschlange.
    @Test
    void should_putANoteBack_whenItsUpdatesCannotBeRead() {
        var note = noteWith("Inhalt");
        var unreachable = new de.tstieh.stoneintelligence.platform.sync.relay.InterceptingSnapshotStoreFailing(snapshots);
        var indexer = new NoteTextIndexer(index, unreachable, () -> bridge);

        indexer.runOnce();

        assertThat(index.queue).extracting(NoteTextIndex.Queued::noteId).containsExactly(note);
        assertThat(index.texts).isEmpty();
    }

    // Unlesbarer Inhalt: dauerhaft - erneut versuchen hiesse alle 3 s dieselbe Arbeit, bis die naechste Aenderung kommt.
    @Test
    void should_dropANote_whoseContentCannotBeTurnedIntoText() {
        var note = NoteId.newId();
        snapshots.append(note, new byte[] {1, 2, 3}, false, "tom");
        index.enqueue(note, vault);

        indexer.runOnce();

        assertThat(index.queue).isEmpty();
        assertThat(index.texts).doesNotContainKey(note);
    }

    @Test
    void should_workThroughTheQueueInBatches() {
        for (int i = 0; i < NoteTextIndexer.BATCH + 3; i++) {
            noteWith("Notiz " + i);
        }

        assertThat(indexer.runOnce()).isEqualTo(NoteTextIndexer.BATCH);
        assertThat(indexer.runOnce()).isEqualTo(3);
        assertThat(index.texts).hasSize(NoteTextIndexer.BATCH + 3);
        assertThat(List.copyOf(index.queue)).isEmpty();
    }
}
