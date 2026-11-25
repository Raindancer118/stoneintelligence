package de.tstieh.stoneintelligence.platform.comment;

import java.time.Instant;
import java.util.UUID;

/** {@code editedAt == null}: nie bearbeitet. */
public record Comment(UUID id, UUID threadId, String author, String body, Instant createdAt, Instant editedAt) {
}
