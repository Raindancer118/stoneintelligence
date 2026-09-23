package de.raindancer118.stoneintelligence.platform.files;

import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository;
import de.raindancer118.stoneintelligence.platform.vault.NoteKind;

class FakeFileVersionRepositoryTest extends FileVersionRepositoryContractTest {

    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeFileVersionRepository versions = new FakeFileVersionRepository(notes);

    @Override
    protected FileVersionRepository repository() {
        return versions;
    }

    @Override
    protected NoteId newFile(VaultId vaultId) {
        return notes.create(vaultId, java.util.UUID.randomUUID() + ".pdf", NoteLevel.of(1), "tom", NoteKind.FILE).id();
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }

    @Override
    protected void deleteFile(VaultId vaultId, NoteId noteId) {
        notes.delete(vaultId, noteId, java.util.UUID.randomUUID().toString(), "tom");
    }
}
