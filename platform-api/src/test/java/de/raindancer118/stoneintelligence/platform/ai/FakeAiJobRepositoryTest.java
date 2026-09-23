package de.raindancer118.stoneintelligence.platform.ai;

import de.raindancer118.stoneintelligence.domain.id.VaultId;

class FakeAiJobRepositoryTest extends AiJobRepositoryContractTest {

    @Override
    protected AiJobRepository repository() {
        return new FakeAiJobRepository();
    }

    @Override
    protected VaultId existingVault() {
        return VaultId.newId();
    }
}
