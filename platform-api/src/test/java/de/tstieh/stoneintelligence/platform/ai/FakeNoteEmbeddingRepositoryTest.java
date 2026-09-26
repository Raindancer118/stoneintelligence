package de.tstieh.stoneintelligence.platform.ai;

import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;

class FakeNoteEmbeddingRepositoryTest extends NoteEmbeddingRepositoryContractTest {

    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeNoteEmbeddingRepository repository = new FakeNoteEmbeddingRepository(notes);

    @Override
    protected NoteEmbeddingRepository repository() {
        return repository;
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }

    @Override
    protected NoteId newNote(VaultId vaultId, String path) {
        return notes.create(vaultId, path, NoteLevel.of(1), "tom").id();
    }

    @Override
    protected void deleteNote(VaultId vaultId, NoteId noteId) {
        notes.delete(vaultId, noteId, UUID.randomUUID().toString(), "tom");
    }
}
