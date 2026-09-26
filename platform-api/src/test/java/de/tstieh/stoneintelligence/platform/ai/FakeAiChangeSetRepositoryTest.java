package de.tstieh.stoneintelligence.platform.ai;

import de.tstieh.stoneintelligence.domain.id.VaultId;

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
