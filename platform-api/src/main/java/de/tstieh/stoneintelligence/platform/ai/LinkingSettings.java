package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Wie ein Vault verlinkt wird (ADR 0012). Standard: aus; wenn an, auch in Notizen von Menschen und
 * ohne Hoechstzahl. {@code requestedBy}: in wessen Namen und mit wessen Rechten der Lauf handelt -
 * die Person, die die Einstellung zuletzt gesetzt hat.
 */
public record LinkingSettings(VaultId vaultId, boolean enabled, Mode mode, boolean linkHumanNotes, Integer maxLinksPerNote,
                              String service, String requestedBy, Instant lastRunAt, Instant updatedAt) {

    /** {@code AI}: Kandidaten prueft eine KI (Stufe 3); {@code SEMANTIC}: nur Stufen 1 und 2, nichts verlaesst den Server. */
    public enum Mode { AI, SEMANTIC }

    public LinkingSettings {
        if (maxLinksPerNote != null && maxLinksPerNote < 1) {
            throw new AiWriteRefusedException("Die Höchstzahl je Notiz muss mindestens 1 sein");
        }
    }

    public static LinkingSettings defaults(VaultId vaultId, String requestedBy, Instant now) {
        return new LinkingSettings(vaultId, false, Mode.AI, true, null, null, requestedBy, null, now);
    }
}
