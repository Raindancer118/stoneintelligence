package de.raindancer118.stoneintelligence.platform.ai;

import de.raindancer118.stoneintelligence.domain.id.VaultId;

public record NewAiJob(VaultId vaultId, String service, String requestedBy, String fileName, String contentType, int level,
                       byte[] content, int maxAttempts) {
}
