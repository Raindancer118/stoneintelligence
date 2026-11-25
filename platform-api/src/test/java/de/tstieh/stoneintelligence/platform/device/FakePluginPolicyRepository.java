package de.tstieh.stoneintelligence.platform.device;

import java.util.HashMap;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakePluginPolicyRepository implements PluginPolicyRepository {

    private final Map<VaultId, PluginPolicy> policies = new HashMap<>();

    @Override
    public PluginPolicy find(VaultId vaultId) {
        return policies.getOrDefault(vaultId, PluginPolicy.off(vaultId));
    }

    @Override
    public void save(PluginPolicy policy, String actor) {
        policies.put(policy.vaultId(), policy);
    }
}
