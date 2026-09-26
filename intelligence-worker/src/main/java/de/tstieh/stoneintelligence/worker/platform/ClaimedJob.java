package de.tstieh.stoneintelligence.worker.platform;

import java.util.UUID;

/** Ein Job, wie ihn platform-api dem Worker gibt ({@code POST /internal/ai/jobs/claim}). */
public record ClaimedJob(UUID jobId, String vaultId, String service, String requestedBy, String fileName, String contentType,
                         long size, int level, UUID changeSetId, int attempt) {
}
