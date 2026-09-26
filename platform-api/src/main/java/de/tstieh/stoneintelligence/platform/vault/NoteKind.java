package de.tstieh.stoneintelligence.platform.vault;

/**
 * Was unter einem Pfad liegt: eine Markdown-Notiz (Inhalt als Yjs-CRDT) oder eine Datei (PDF,
 * Bild, Anhang - Inhalt als Versionen im Dateispeicher, ADR 0009).
 */
public enum NoteKind { NOTE, FILE }
