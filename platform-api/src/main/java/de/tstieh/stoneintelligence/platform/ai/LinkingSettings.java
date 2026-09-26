package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Wie ein Vault verlinkt wird (ADR 0012). Standard: aus; wenn an, auch in Notizen von Menschen und
 * ohne Hoechstzahl. {@code requestedBy}: in wessen Namen und mit wessen Rechten der Lauf handelt -
 * die Person, die die Einstellung zuletzt gesetzt hat.
 */
public record LinkingSettings(VaultId vaultId, boolean enabled, Mode mode, boolean linkHumanNotes, Integer maxLinksPerNote,
                              String service, String requestedBy, Instant lastRunAt, Instant updatedAt,
                              java.util.Set<String> aiConsents) {

    /**
     * {@code LITERAL}: nur woertliche Nennungen (Stufe 1); {@code SEMANTIC}: dazu aehnliche Inhalte
     * (Stufe 2, lokal - nichts verlaesst den Server); {@code AI}: dazu KI-Pruefung (Stufe 3, folgt).
     */
    public enum Mode { LITERAL, SEMANTIC, AI }

    public LinkingSettings {
        if (maxLinksPerNote != null && maxLinksPerNote < 1) {
            throw new AiWriteRefusedException("Die Höchstzahl je Notiz muss mindestens 1 sein");
        }
        aiConsents = aiConsents == null ? java.util.Set.of() : java.util.Set.copyOf(aiConsents);
    }

    /** Ohne Einwilligungen - die liegen getrennt ({@link LinkingSettingsRepository#aiConsents}). */
    public LinkingSettings(VaultId vaultId, boolean enabled, Mode mode, boolean linkHumanNotes, Integer maxLinksPerNote,
                           String service, String requestedBy, Instant lastRunAt, Instant updatedAt) {
        this(vaultId, enabled, mode, linkHumanNotes, maxLinksPerNote, service, requestedBy, lastRunAt, updatedAt, java.util.Set.of());
    }

    public LinkingSettings withAiConsents(java.util.Set<String> consents) {
        return new LinkingSettings(vaultId, enabled, mode, linkHumanNotes, maxLinksPerNote, service, requestedBy, lastRunAt, updatedAt,
            consents);
    }

    public static LinkingSettings defaults(VaultId vaultId, String requestedBy, Instant now) {
        return new LinkingSettings(vaultId, false, Mode.LITERAL, true, null, null, requestedBy, null, now);
    }
}
