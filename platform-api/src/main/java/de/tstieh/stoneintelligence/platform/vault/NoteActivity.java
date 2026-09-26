package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;

/**
 * Wer einen Eintrag angelegt, zuletzt bearbeitet und zuletzt geoeffnet hat (Anforderungen.md:
 * "Oeffnen =/ Bearbeitung"). {@code null}, solange es das noch nicht gab.
 */
public record NoteActivity(String createdBy, Instant createdAt, String lastEditedBy, Instant lastEditedAt,
                           String lastOpenedBy, Instant lastOpenedAt) {
}
