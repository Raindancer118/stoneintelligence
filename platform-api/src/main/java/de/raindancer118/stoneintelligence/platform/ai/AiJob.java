package de.raindancer118.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/**
 * Ein hochgeladenes Dokument, das ein KI-Dienst verarbeiten soll (ADR 0008). Das Dokument selbst
 * liegt getrennt ({@link AiJobRepository#content}) und wird geloescht, sobald der Job endet.
 * {@code level} ist das Level des Quelldokuments - alle daraus erzeugten Notizen bekommen es.
 * {@code waitingForCapacity}: wartet, bis der KI-Dienst wieder Kontingent hat ({@code availableAt}).
 */
public record AiJob(UUID id, VaultId vaultId, String service, String requestedBy, String fileName, String contentType,
                    long size, int level, Status status, int attempts, int maxAttempts, Instant availableAt,
                    Instant leaseUntil, String progress, Integer percent, String error, UUID changeSetId,
                    Instant createdAt, Instant finishedAt, boolean waitingForCapacity) {

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public boolean open() {
        return status == Status.PENDING || status == Status.RUNNING;
    }
}
