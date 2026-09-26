package de.tstieh.stoneintelligence.platform.identity;

import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;

class FakeAccessGrantRepositoryTest extends AccessGrantRepositoryContractTest {

    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository repository = new FakeAccessGrantRepository(notes);

    @Override
    protected AccessGrantRepository repository() {
        return repository;
    }

    @Override
    protected VaultId newVault() {
        return VaultId.of(UUID.randomUUID());
    }

    @Override
    protected NoteId newNote(VaultId vaultId, String path) {
        return notes.create(vaultId, path, NoteLevel.of(1), "tom").id();
    }

    @Override
    protected void renameNote(VaultId vaultId, NoteId noteId, String path) {
        notes.rename(vaultId, noteId, path);
    }

    @Override
    protected void deleteNote(VaultId vaultId, NoteId noteId) {
        notes.delete(vaultId, noteId, UUID.randomUUID().toString(), "tom");
    }
}
