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

    public enum Kind { CREATED, UPDATED }
}
