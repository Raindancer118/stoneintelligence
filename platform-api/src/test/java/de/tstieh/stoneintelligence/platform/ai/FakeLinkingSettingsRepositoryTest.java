package de.tstieh.stoneintelligence.platform.ai;

import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeLinkingSettingsRepositoryTest extends LinkingSettingsRepositoryContractTest {

    private final FakeLinkingSettingsRepository repository = new FakeLinkingSettingsRepository();

    @Override
    protected LinkingSettingsRepository repository() {
        return repository;
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }
}
