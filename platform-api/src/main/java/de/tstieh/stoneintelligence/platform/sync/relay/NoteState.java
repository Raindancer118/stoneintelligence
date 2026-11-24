package de.tstieh.stoneintelligence.platform.sync.relay;

/** Alle Updates einer Notiz bis einschliesslich {@code revision}, zu einem zusammengefuehrt (ADR 0013, Schritt 6). */
public record NoteState(long revision, byte[] payload) {
}
