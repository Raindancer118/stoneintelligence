package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;

/**
 * Eine KI-Operation an genau einer Notiz, mit dem exakten Text davor und danach - Grundlage fuer
 * das konfliktgepruefte Rueckgaengigmachen (ADR 0008).
 */
public record AiChange(UUID id, UUID changeSetId, NoteId noteId, String path, Kind kind,
                       String textBefore, String textAfter, Instant at,
                       java.util.List<de.tstieh.stoneintelligence.domain.link.LinkText.Insertion> links) {

    /**
     * {@code FILE_CREATED}: eine Datei (das gelesene Original); {@code textAfter} ist ihr SHA-256.
     * {@code LINKED}: gesetzte Links (ADR 0012) - nur {@link #links()} zaehlt, die Texte bleiben leer.
     */
    public enum Kind { CREATED, UPDATED, FILE_CREATED, LINKED }

    public AiChange {
        links = links == null ? java.util.List.of() : java.util.List.copyOf(links);
    }

    public AiChange(UUID id, UUID changeSetId, NoteId noteId, String path, Kind kind, String textBefore, String textAfter, Instant at) {
        this(id, changeSetId, noteId, path, kind, textBefore, textAfter, at, java.util.List.of());
    }
}
