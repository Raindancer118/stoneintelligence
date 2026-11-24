package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.ArrayList;
import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoteStateCompactorTest {

    private static YjsBridge bridge;

    @BeforeAll
    static void load() {
        bridge = YjsBridge.load();
    }

    @AfterAll
    static void close() {
        bridge.close();
    }

    private final FakeSnapshotStore store = new FakeSnapshotStore();
    private final NoteId note = NoteId.newId();
    private int written;

    private void write(int edits) {
        var history = new ArrayList<byte[]>();
        store.current(note).forEach(update -> history.add(update.payload()));
        for (int i = 0; i < edits; i++) {
            var update = bridge.change(history, "Fassung " + (++written)).orElseThrow();
            history.add(update);
            store.append(note, update, false, "tom");
        }
    }

    private String text() {
        return bridge.textOf(store.current(note).stream().map(UpdateRecord::payload).toList());
    }

    @Test
    void should_storeTheMergedState_whenAJoinerGotManyUpdates() {
        write(12);
        var compactor = new NoteStateCompactor(store, () -> bridge, 10, Runnable::run);

        compactor.joined(note, store.current(note));

        assertThat(store.state(note)).hasValueSatisfying(state -> assertThat(state.revision()).isEqualTo(12));
        assertThat(store.current(note)).hasSize(1);
        assertThat(text()).isEqualTo("Fassung 12");
        assertThat(store.listSince(note, 0)).hasSize(12);
    }

    @Test
    void should_buildOnTheStoredState_withTheUpdatesAfterIt() {
        write(12);
        var compactor = new NoteStateCompactor(store, () -> bridge, 10, Runnable::run);
        compactor.joined(note, store.current(note));
        write(11);

        compactor.joined(note, store.current(note));

        assertThat(store.state(note)).hasValueSatisfying(state -> assertThat(state.revision()).isEqualTo(23));
        assertThat(text()).isEqualTo("Fassung 23");
    }

    @Test
    void should_leaveShortHistoriesAlone() {
        write(5);
        var compactor = new NoteStateCompactor(store, () -> bridge, 10, Runnable::run);

        compactor.joined(note, store.current(note));

        assertThat(store.state(note)).isEmpty();
    }

    @Test
    void should_neverMergeEncryptedUpdates() {
        for (int i = 0; i < 12; i++) {
            store.append(note, new byte[] {1, 2, 3}, true, "tom");
        }
        var compactor = new NoteStateCompactor(store, () -> bridge, 10, Runnable::run);

        compactor.joined(note, store.current(note));

        assertThat(store.state(note)).isEmpty();
    }

    @Test
    void should_runOneCompactionPerNoteAtATime() {
        write(12);
        List<Runnable> queued = new ArrayList<>();
        var compactor = new NoteStateCompactor(store, () -> bridge, 10, queued::add);

        compactor.joined(note, store.current(note));
        compactor.joined(note, store.current(note));
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        compactor.joined(note, store.current(note));

        assertThat(store.state(note)).isPresent();
        assertThat(queued).hasSize(1);
    }
}
