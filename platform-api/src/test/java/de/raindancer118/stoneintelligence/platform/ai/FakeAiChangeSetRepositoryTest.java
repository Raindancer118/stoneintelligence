package de.raindancer118.stoneintelligence.platform.ai;

import de.raindancer118.stoneintelligence.domain.id.VaultId;

class FakeAiChangeSetRepositoryTest extends AiChangeSetRepositoryContractTest {

    @Override
    protected AiChangeSetRepository repository() {
        return new FakeAiChangeSetRepository();
    }

    @Override
    protected VaultId existingVault() {
        return VaultId.newId();
    }
}
