package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public interface LinkingSettingsRepository {

    Optional<LinkingSettings> find(VaultId vaultId);

    void save(LinkingSettings settings);

    /** Vaults, in denen die naechtliche Verlinkung eingeschaltet ist. */
    List<LinkingSettings> enabled();

    void markRun(VaultId vaultId, Instant at);
}
