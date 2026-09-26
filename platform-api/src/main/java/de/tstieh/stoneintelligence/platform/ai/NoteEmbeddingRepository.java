package de.tstieh.stoneintelligence.platform.ai;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Abschnitts-Vektoren je Notiz (ADR 0012) - Grundlage fuer Stufe 2 und "Aehnliche Notizen". */
public interface NoteEmbeddingRepository {

    /** Ein Abschnitt einer Notiz; {@code vector} ist auf Laenge 1 normiert. */
    record Chunk(int index, String heading, float[] vector) {
    }

    /** Mit welchem Modell und aus welchem Text die Vektoren einer Notiz stammen. */
    record State(NoteId noteId, String model, String contentHash) {
    }

    /** Eine aehnliche Notiz: ihr aehnlichster Abschnitt und dessen Kosinus-Aehnlichkeit (1 = gleich). */
    record Similar(NoteId noteId, int chunk, String heading, double similarity) {
    }

    List<State> states(VaultId vaultId);

    /** Ersetzt alle Abschnitte der Notiz. */
    void replace(VaultId vaultId, NoteId noteId, String model, String contentHash, List<Chunk> chunks);

    /** Notizen, deren Abschnitte einem Abschnitt von {@code noteId} am naechsten liegen - die Notiz selbst nicht, beste zuerst. */
    List<Similar> similarTo(VaultId vaultId, NoteId noteId, int limit);
}
