package de.raindancer118.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;

/**
 * Eine KI-Operation an genau einer Notiz, mit dem exakten Text davor und danach - Grundlage fuer
 * das konfliktgepruefte Rueckgaengigmachen (ADR 0008).
 */
public record AiChange(UUID id, UUID changeSetId, NoteId noteId, String path, Kind kind,
                       String textBefore, String textAfter, Instant at) {

    /** {@code FILE_CREATED}: eine Datei (das gelesene Original); {@code textAfter} ist ihr SHA-256. */
    public enum Kind { CREATED, UPDATED, FILE_CREATED }
}
