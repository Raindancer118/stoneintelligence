package de.tstieh.stoneintelligence.platform.ai;

import de.tstieh.stoneintelligence.domain.id.VaultId;

public record NewAiJob(VaultId vaultId, String service, String requestedBy, String fileName, String contentType, int level,
                       byte[] content, int maxAttempts, AiJob.Kind kind) {

    public NewAiJob(VaultId vaultId, String service, String requestedBy, String fileName, String contentType, int level,
                    byte[] content, int maxAttempts) {
        this(vaultId, service, requestedBy, fileName, contentType, level, content, maxAttempts, AiJob.Kind.INGEST);
    }

    /** Groesse des Dokuments; ein Verlinkungslauf hat keins. */
    public long size() {
        return content == null ? 0 : content.length;
    }
}
