package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;

/**
 * Persistiert rohe Yjs-Update-Blobs je Note (Plan.md Abschnitt 8.4: der Yjs-CRDT-Zustand ist
 * Source of Truth, die DB speichert nur Snapshots/History davon). Der Server bleibt "dumm":
 * er speichert und relayt Bytes, ohne sie als CRDT zu interpretieren.
 */
public interface SnapshotStore {

    /** {@code actor}: wer das Update geschrieben hat (Person, {@code ki:…}); Grundlage des Versionsverlaufs. */
    UpdateRecord append(NoteId noteId, byte[] payload, boolean ciphertext, String actor);

    java.util.Optional<UpdateRecord> appendIfCurrent(NoteId noteId, long expectedRevision, byte[] payload, String actor);

    List<UpdateRecord> listSince(NoteId noteId, long afterServerSequence);

    /** Updates bis einschliesslich {@code serverSequence} - der Stand der Notiz zu dieser Revision. */
    List<UpdateRecord> listUpTo(NoteId noteId, long serverSequence);

    /** Wer wann geschrieben hat, ohne die Inhalte, aelteste zuerst. */
    List<UpdateInfo> log(NoteId noteId);

    /**
     * Hoechste {@code server_sequence} je Note (noch ohne Updates = 0) - EINE Abfrage fuer eine
     * ganze Listen-Seite, damit Clients Aenderungen erkennen, ohne jede Notiz einzeln joinen und
     * ihre Historie laden zu muessen.
     */
    java.util.Map<NoteId, Long> latestRevisions(java.util.Collection<NoteId> noteIds);

    /** Der zuletzt abgelegte zusammengefuehrte Zustand, falls einer existiert. */
    java.util.Optional<NoteState> state(NoteId noteId);

    /** Legt den Zustand ab; ein aelterer als der vorhandene wird ignoriert (Compaction-Laeufe koennen sich ueberholen). */
    void saveState(NoteId noteId, long revision, byte[] state);

    /**
     * Was ein Client zum Beitreten braucht: der Zustand (als Update mit seiner Revision) und alle
     * Updates danach. Ohne Zustand die ganze Historie. Gleichwertig zu {@code listSince(noteId, 0)}.
     */
    default List<UpdateRecord> current(NoteId noteId) {
        var state = state(noteId);
        if (state.isEmpty()) {
            return listSince(noteId, 0);
        }
        var updates = new java.util.ArrayList<UpdateRecord>();
        updates.add(new UpdateRecord(state.get().revision(), state.get().payload(), false));
        updates.addAll(listSince(noteId, state.get().revision()));
        return updates;
    }
}
