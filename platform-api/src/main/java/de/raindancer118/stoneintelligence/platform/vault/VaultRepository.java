package de.raindancer118.stoneintelligence.platform.vault;

import java.util.Optional;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

public interface VaultRepository {

    Vault create(String name);

    Optional<Vault> findById(VaultId id);
}
