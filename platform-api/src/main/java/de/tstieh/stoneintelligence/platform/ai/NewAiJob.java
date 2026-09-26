package de.tstieh.stoneintelligence.platform.ai;

import de.tstieh.stoneintelligence.domain.id.VaultId;

public record NewAiJob(VaultId vaultId, String service, String requestedBy, String fileName, String contentType, int level,
                       byte[] content, int maxAttempts) {
}
