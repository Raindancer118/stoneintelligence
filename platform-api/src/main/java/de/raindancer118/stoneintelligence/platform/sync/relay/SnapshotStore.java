package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;

/**
 * Persistiert rohe Yjs-Update-Blobs je Note (Plan.md Abschnitt 8.4: der Yjs-CRDT-Zustand ist
 * Source of Truth, die DB speichert nur Snapshots/History davon). Der Server bleibt "dumm":
 * er speichert und relayt Bytes, ohne sie als CRDT zu interpretieren.
 */
public interface SnapshotStore {

    UpdateRecord append(NoteId noteId, byte[] payload, boolean ciphertext);

    java.util.Optional<UpdateRecord> appendIfCurrent(NoteId noteId, long expectedRevision, byte[] payload);

    List<UpdateRecord> listSince(NoteId noteId, long afterServerSequence);
}
