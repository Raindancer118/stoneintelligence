package de.raindancer118.stoneintelligence.platform.identity;

import de.raindancer118.stoneintelligence.domain.id.VaultId;

class FakeAuthorizationRepositoryTest extends AuthorizationRepositoryContractTest {

    private final FakeAuthorizationRepository repository = new FakeAuthorizationRepository();

    @Override
    protected AuthorizationRepository repository() {
        return repository;
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }
}
