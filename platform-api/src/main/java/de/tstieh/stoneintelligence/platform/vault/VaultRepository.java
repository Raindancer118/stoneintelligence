package de.tstieh.stoneintelligence.platform.vault;

import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public interface VaultRepository {

    Vault create(String name);

    Optional<Vault> findById(VaultId id);

    /** Neuer Name; leer, wenn es den Vault nicht gibt. */
    Optional<Vault> rename(VaultId id, String name);
}
