package de.tstieh.stoneintelligence.platform.vault;

import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeNoteRepositoryTest extends NoteRepositoryContractTest {

    private final FakeNoteRepository repository = new FakeNoteRepository();

    @Override
    protected NoteRepository repository() {
        return repository;
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }
}
