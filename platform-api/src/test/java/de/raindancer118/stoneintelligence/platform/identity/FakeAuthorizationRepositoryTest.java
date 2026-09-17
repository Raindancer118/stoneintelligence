package de.raindancer118.stoneintelligence.platform.identity;

class FakeAuthorizationRepositoryTest extends AuthorizationRepositoryContractTest {

    private final FakeAuthorizationRepository repository = new FakeAuthorizationRepository();

    @Override
    protected AuthorizationRepository repository() {
        return repository;
    }
}
