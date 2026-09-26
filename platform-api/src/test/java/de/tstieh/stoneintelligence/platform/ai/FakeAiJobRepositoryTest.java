package de.tstieh.stoneintelligence.platform.ai;

import de.tstieh.stoneintelligence.domain.id.VaultId;

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
