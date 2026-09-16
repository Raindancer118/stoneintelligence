package de.raindancer118.stoneintelligence.platform.vault;

class FakeNoteRepositoryTest extends NoteRepositoryContractTest {

    private final FakeNoteRepository repository = new FakeNoteRepository();

    @Override
    protected NoteRepository repository() {
        return repository;
    }
}
