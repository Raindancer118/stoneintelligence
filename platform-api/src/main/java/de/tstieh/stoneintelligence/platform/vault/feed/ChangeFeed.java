package de.tstieh.stoneintelligence.platform.vault.feed;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Was sich in einem Vault seit einem Cursor geaendert hat - roh, ohne Rechtepruefung (ADR 0013). */
public interface ChangeFeed {

    /** Ab hier beginnt ein Geraet, bevor es die volle Liste holt - so geht dazwischen nichts verloren. */
    long now();

    /**
     * Hoechstens {@code limit} geaenderte Eintraege (auch geloeschte). Ordner und Rechte-Ereignisse
     * kommen nur mit der letzten Seite ({@code more == false}), dann fuer den ganzen Bereich.
     */
    Batch read(VaultId vaultId, FeedCursor cursor, int limit);

    record Batch(List<NoteId> changed, boolean more, FeedCursor next, List<String> foldersChanged,
                 List<String> foldersRemoved, List<AccessEvent> accessEvents) {
    }

    /** Eine Freigabe oder Mitgliedschaft hat sich geaendert. {@code folderPath ""} = der ganze Vault. */
    record AccessEvent(String scopeType, String scopeSubject, String folderPath, NoteId noteId) {
    }
}
