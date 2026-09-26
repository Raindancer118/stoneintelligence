package de.tstieh.stoneintelligence.platform.files;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.NoteId;

/** Eine Fassung einer Datei; die Bytes liegen im {@link BlobStore} unter {@code sha256}. */
public record FileVersion(NoteId noteId, long revision, String sha256, long size, String contentType, String createdBy,
                          Instant createdAt) {
}
