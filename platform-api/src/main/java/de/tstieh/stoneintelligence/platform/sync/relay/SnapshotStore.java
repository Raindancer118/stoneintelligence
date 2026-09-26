package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;

/**
 * Persistiert rohe Yjs-Update-Blobs je Note (Plan.md Abschnitt 8.4: der Yjs-CRDT-Zustand ist
 * Source of Truth, die DB speichert nur Snapshots/History davon). Der Server bleibt "dumm":
 * er speichert und relayt Bytes, ohne sie als CRDT zu interpretieren.
 */
public interface SnapshotStore {

    UpdateRecord append(NoteId noteId, byte[] payload, boolean ciphertext);

    java.util.Optional<UpdateRecord> appendIfCurrent(NoteId noteId, long expectedRevision, byte[] payload);

    List<UpdateRecord> listSince(NoteId noteId, long afterServerSequence);

    /**
     * Hoechste {@code server_sequence} je Note (noch ohne Updates = 0) - EINE Abfrage fuer eine
     * ganze Listen-Seite, damit Clients Aenderungen erkennen, ohne jede Notiz einzeln joinen und
     * ihre Historie laden zu muessen.
     */
    java.util.Map<NoteId, Long> latestRevisions(java.util.Collection<NoteId> noteIds);
}
