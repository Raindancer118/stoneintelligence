package de.tstieh.stoneintelligence.platform.vault;

import java.util.Locale;

/**
 * Was unter einem Pfad liegt: eine Markdown-Notiz (Inhalt als Yjs-CRDT), eine Datei (PDF, Bild,
 * Anhang - Inhalt als Versionen im Dateispeicher, ADR 0009) oder ein Canvas (JSON Canvas als
 * Yjs-Dokument aus Karten und Kanten, ADR 0018). Ein Canvas wird wie eine Notiz synchronisiert,
 * hat aber keinen Text: Inhalts-, Versions- und Suchfunktionen gibt es fuer ihn nicht.
 */
public enum NoteKind {
    NOTE, FILE, CANVAS;

    public static final String CANVAS_EXTENSION = ".canvas";

    /** Art eines Eintrags mit Yjs-Inhalt anhand seines Pfads - Notizen enden immer auf .md (NotePaths). */
    public static NoteKind ofYjsPath(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(CANVAS_EXTENSION) ? CANVAS : NOTE;
    }
}
