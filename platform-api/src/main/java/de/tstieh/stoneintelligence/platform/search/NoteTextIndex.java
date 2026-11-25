package de.tstieh.stoneintelligence.platform.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Klartext der Notizen fuer die Suche - ein Index, keine Quelle (die bleibt das Yjs-Log). */
public interface NoteTextIndex {

    record Queued(NoteId noteId, VaultId vaultId) { }

    /** Holt bis zu {@code limit} Notizen aus der Warteschlange; andere Instanzen bekommen andere. */
    List<Queued> claim(int limit);

    /** Zurueck in die Warteschlange, wenn das Indizieren scheiterte. */
    void requeue(NoteId noteId);

    void upsert(NoteId noteId, VaultId vaultId, long revision, String text);

    void remove(NoteId noteId);

    /** Notizen, deren Inhalt zur tsquery passt, beste zuerst. */
    List<NoteId> matching(VaultId vaultId, String tsquery, int limit);

    /** Textstelle je Notiz; Treffer stehen zwischen {@link #MARK_START} und {@link #MARK_END}. */
    Map<NoteId, String> excerpts(VaultId vaultId, Collection<NoteId> noteIds, String tsquery);

    /** Der indizierte Klartext je Notiz (nur Notizen dieses Vaults mit Text). */
    Map<NoteId, String> bodies(VaultId vaultId, Collection<NoteId> noteIds);

    String MARK_START = "\u0002";
    String MARK_END = "\u0003";
}
