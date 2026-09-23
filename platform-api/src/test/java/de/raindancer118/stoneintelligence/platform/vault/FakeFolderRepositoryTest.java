package de.raindancer118.stoneintelligence.platform.vault;

import de.raindancer118.stoneintelligence.domain.id.VaultId;

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
