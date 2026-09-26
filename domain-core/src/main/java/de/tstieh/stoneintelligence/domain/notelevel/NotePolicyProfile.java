package de.tstieh.stoneintelligence.domain.notelevel;

import java.util.Objects;

/**
 * Das versionierte Policy-Profil hinter einem {@link NoteLevel} (Plan.md Abschnitt 4.1).
 * {@code schemaVersion} erlaubt es, das Profil-Schema künftig zu erweitern, ohne bestehende
 * gespeicherte Profile stillschweigend falsch zu interpretieren.
 */
public record NotePolicyProfile(
    int schemaVersion,
    Classification classification,
    SyncMode sync,
    boolean serverReadable,
    AgentProcessing agentProcessing,
    Sharing sharing
) {

    public NotePolicyProfile {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, was " + schemaVersion);
        }
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(sync, "sync must not be null");
        Objects.requireNonNull(agentProcessing, "agentProcessing must not be null");
        Objects.requireNonNull(sharing, "sharing must not be null");

        if (serverReadable && sync == SyncMode.E2EE) {
            throw new IllegalArgumentException(
                "serverReadable must be false when sync is E2EE - the server may only ever see ciphertext");
        }
        if (serverReadable && sync == SyncMode.DENIED) {
            throw new IllegalArgumentException(
                "serverReadable must be false when sync is DENIED - content that never leaves the client "
                    + "cannot be server-readable");
        }
        if (agentProcessing == AgentProcessing.ALLOWED && !serverReadable) {
            throw new IllegalArgumentException(
                "agentProcessing cannot be ALLOWED when the server cannot read the content "
                    + "(serverReadable=false)");
        }
    }
}
