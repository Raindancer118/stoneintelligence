package de.tstieh.stoneintelligence.platform.device;

import de.tstieh.stoneintelligence.domain.id.VaultId;

public interface PluginPolicyRepository {

    /** Ohne gespeicherte Richtlinie: {@link PluginPolicy#off}. */
    PluginPolicy find(VaultId vaultId);

    void save(PluginPolicy policy, String actor);
}
