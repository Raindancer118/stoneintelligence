package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

public record Vault(VaultId id, String name, Instant createdAt) {
}
