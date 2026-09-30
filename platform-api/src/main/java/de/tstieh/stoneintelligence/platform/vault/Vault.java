package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** {@code selectiveSync}: Geraete synchronisieren nur gewaehlte Arbeitsbereiche (ADR 0013, grosse Vaults). */
public record Vault(VaultId id, String name, Instant createdAt, boolean selectiveSync) {

    public Vault(VaultId id, String name, Instant createdAt) {
        this(id, name, createdAt, false);
    }
}
