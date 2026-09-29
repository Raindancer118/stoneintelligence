package de.tstieh.stoneintelligence.platform.sync.relay;

import de.tstieh.stoneintelligence.domain.id.NoteId;

class FakeSnapshotStoreTest extends SnapshotStoreContractTest {

    @Override
    protected SnapshotStore store() {
        return new FakeSnapshotStore();
    }

    @Override
    protected NoteId newNote() {
        return NoteId.newId();
    }
}
