package de.raindancer118.stoneintelligence.platform.files;

import java.time.Instant;
import de.raindancer118.stoneintelligence.domain.id.NoteId;

/** Eine Fassung einer Datei; die Bytes liegen im {@link BlobStore} unter {@code sha256}. */
public record FileVersion(NoteId noteId, long revision, String sha256, long size, String contentType, String createdBy,
                          Instant createdAt) {
}
