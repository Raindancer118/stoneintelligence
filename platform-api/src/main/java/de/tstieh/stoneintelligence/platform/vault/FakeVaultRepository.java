package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public class FakeVaultRepository implements VaultRepository {

    private final Map<VaultId, Vault> vaults = new ConcurrentHashMap<>();

    @Override
    public Vault create(String name) {
        var vault = new Vault(VaultId.newId(), name, Instant.now());
        vaults.put(vault.id(), vault);
        return vault;
    }

    @Override
    public Optional<Vault> findById(VaultId id) {
        return Optional.ofNullable(vaults.get(id));
    }

    @Override
    public Optional<Vault> rename(VaultId id, String name) {
        return Optional.ofNullable(vaults.computeIfPresent(id, (key, vault) -> new Vault(key, name, vault.createdAt())));
    }
}
