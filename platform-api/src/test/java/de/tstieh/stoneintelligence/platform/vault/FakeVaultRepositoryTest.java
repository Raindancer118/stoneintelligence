package de.tstieh.stoneintelligence.platform.vault;

class FakeVaultRepositoryTest extends VaultRepositoryContractTest {

    private final FakeVaultRepository repository = new FakeVaultRepository();

    @Override
    protected VaultRepository repository() {
        return repository;
    }
}
