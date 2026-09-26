package de.tstieh.stoneintelligence.domain.notelevel;

/**
 * Das Nutzer-Kürzel 1..101 aus Anforderungen.md. Bewusst nur ein Wertebereich mit klaren
 * Sonderpunkten (100 = keine Synchronisation, 101 = E2EE) - die eigentliche Policy steckt
 * in {@link NotePolicyProfile}, nicht in dieser Zahl (vgl. Plan.md Abschnitt 4.1).
 */
public record NoteLevel(int value) {

    public static final int MIN = 1;
    public static final int MAX = 101;
    public static final int NO_SYNC = 100;
    public static final int E2EE = 101;

    public NoteLevel {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                "NoteLevel must be between " + MIN + " and " + MAX + ", was " + value);
        }
    }

    public static NoteLevel of(int value) {
        return new NoteLevel(value);
    }

    public boolean isE2ee() {
        return value == E2EE;
    }

    public boolean isNoSync() {
        return value == NO_SYNC;
    }
}
