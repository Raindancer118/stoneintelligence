package de.raindancer118.stoneintelligence.platform.vault;

class FakeVaultRepositoryTest extends VaultRepositoryContractTest {

    private final FakeVaultRepository repository = new FakeVaultRepository();

    @Override
    protected VaultRepository repository() {
        return repository;
    }
}
