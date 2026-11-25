package de.tstieh.stoneintelligence.platform.ask;

import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeQuestionRepositoryTest extends QuestionRepositoryContractTest {

    @Override
    protected QuestionRepository repository() {
        return new FakeQuestionRepository();
    }

    @Override
    protected VaultId existingVault() {
        return VaultId.newId();
    }
}
