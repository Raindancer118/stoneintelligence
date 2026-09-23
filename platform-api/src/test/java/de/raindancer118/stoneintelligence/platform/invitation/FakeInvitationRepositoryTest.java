package de.raindancer118.stoneintelligence.platform.invitation;

import de.raindancer118.stoneintelligence.domain.id.VaultId;

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
