package de.tstieh.stoneintelligence.platform.device;

import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeDeviceRepositoryTest extends DeviceRepositoryContractTest {

    private final FakeDeviceRepository devices = new FakeDeviceRepository();
    private final FakePluginPolicyRepository policies = new FakePluginPolicyRepository();

    @Override
    protected DeviceRepository devices() {
        return devices;
    }

    @Override
    protected PluginPolicyRepository policies() {
        return policies;
    }

    @Override
    protected VaultId newVault() {
        return VaultId.newId();
    }
}
