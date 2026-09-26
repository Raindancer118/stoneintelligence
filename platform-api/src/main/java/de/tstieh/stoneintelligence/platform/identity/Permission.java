package de.tstieh.stoneintelligence.platform.identity;

/** Grundlegende Datei-Berechtigungen (Anforderungen.md: "Lesen, Schreiben, Löschen, Erstellen"). */
public enum Permission {
    READ,
    WRITE,
    DELETE,
    CREATE,
    /** Mitglieder, Gruppen, Rollen, Pfadregeln und Einladungen verwalten (bis V6 hing das an DELETE). */
    MANAGE
}
