package de.tstieh.stoneintelligence.platform.scim;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;

class FakeDirectoryStoreTest extends DirectoryStoreContractTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeDirectoryStore store = new FakeDirectoryStore(authorization);

    @Override
    protected DirectoryStore store() {
        return store;
    }

    @Override
    protected AuthorizationRepository authorization() {
        return authorization;
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }
}
