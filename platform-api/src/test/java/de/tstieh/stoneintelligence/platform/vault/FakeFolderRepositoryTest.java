package de.tstieh.stoneintelligence.platform.vault;

import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeFolderRepositoryTest extends FolderRepositoryContractTest {

    @Override
    protected FolderRepository repository() {
        return new FakeFolderRepository();
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }
}
