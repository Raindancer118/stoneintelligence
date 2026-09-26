package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public record Vault(VaultId id, String name, Instant createdAt) {
}
