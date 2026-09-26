package de.tstieh.stoneintelligence.platform.invitation;

import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeInvitationRepositoryTest extends InvitationRepositoryContractTest {

    @Override
    protected InvitationRepository repository() {
        return new FakeInvitationRepository();
    }

    @Override
    protected VaultId existingVault() {
        return VaultId.newId();
    }
}
