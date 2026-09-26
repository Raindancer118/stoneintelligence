package de.raindancer118.stoneintelligence.platform.identity;

import de.raindancer118.stoneintelligence.domain.id.NoteId;

/**
 * Worauf eine {@link AccessGrant} gilt (ADR 0011): einen Ordner samt allem darunter oder genau
 * einen Eintrag (Notiz oder Datei). Ein Eintrag wird ueber seine stabile {@link NoteId}
 * adressiert, damit die Freigabe Umbenennen und Verschieben uebersteht; {@code path} ist sein
 * aktueller Pfad zum Zeitpunkt des Lesens.
 */
public sealed interface GrantTarget {

    /** {@code path} ohne fuehrende/abschliessende Schraegstriche; {@code ""} ist der ganze Vault. */
    record Folder(String path) implements GrantTarget {
        public Folder {
            path = AccessResolver.normalize(path);
        }
    }

    record Entry(NoteId noteId, String path) implements GrantTarget {
    }

    static GrantTarget folder(String path) {
        return new Folder(path);
    }

    static GrantTarget entry(NoteId noteId, String path) {
        return new Entry(noteId, path);
    }

    /** Gleiches Ziel unabhaengig vom (sich aendernden) Pfad eines Eintrags. */
    default boolean sameTarget(GrantTarget other) {
        return switch (this) {
            case Folder folder -> other instanceof Folder o && o.path().equals(folder.path());
            case Entry entry -> other instanceof Entry o && o.noteId().equals(entry.noteId());
        };
    }
}
