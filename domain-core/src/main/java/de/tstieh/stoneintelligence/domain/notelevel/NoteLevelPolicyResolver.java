package de.tstieh.stoneintelligence.domain.notelevel;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Löst ein {@link NoteLevel} (die UI-Zahl 1..101) in das dahinterliegende
 * {@link NotePolicyProfile} auf. Unveränderlich: {@link #withProfile} liefert eine neue Instanz.
 *
 * <p>Nur die in Anforderungen.md konkret spezifizierten Level (1, 100, 101) sind vorregistriert.
 * Level 2..99 sind laut Plan.md Abschnitt 8 explizit noch offen und werden erst in Phase 8
 * festgelegt - {@link #resolve} rät hier bewusst nicht, sondern wirft
 * {@link UnresolvedNoteLevelException}.
 */
public final class NoteLevelPolicyResolver {

    private final Map<Integer, NotePolicyProfile> profiles;

    private NoteLevelPolicyResolver(Map<Integer, NotePolicyProfile> profiles) {
        this.profiles = Map.copyOf(profiles);
    }

    public static NoteLevelPolicyResolver withDefaults() {
        Map<Integer, NotePolicyProfile> defaults = new HashMap<>();

        // Level 1: "darf von jedem eingesehen und von MCP verarbeitet werden"
        defaults.put(NoteLevel.MIN, new NotePolicyProfile(
            1, Classification.PUBLIC, SyncMode.ALLOWED, true, AgentProcessing.ALLOWED, Sharing.OPEN));

        // Level 100: "keine Synchronisation der Datei"
        defaults.put(NoteLevel.NO_SYNC, new NotePolicyProfile(
            1, Classification.INTERNAL, SyncMode.DENIED, false, AgentProcessing.DENIED, Sharing.RESTRICTED));

        // Level 101: "E2EE. Server sieht ausschließlich Ciphertext"
        defaults.put(NoteLevel.E2EE, new NotePolicyProfile(
            1, Classification.CONFIDENTIAL, SyncMode.E2EE, false, AgentProcessing.DENIED, Sharing.RESTRICTED));

        return new NoteLevelPolicyResolver(defaults);
    }

    public NotePolicyProfile resolve(NoteLevel level) {
        Objects.requireNonNull(level, "level must not be null");
        var profile = profiles.get(level.value());
        if (profile == null) {
            throw new UnresolvedNoteLevelException(level);
        }
        return profile;
    }

    public NoteLevelPolicyResolver withProfile(NoteLevel level, NotePolicyProfile profile) {
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        var copy = new HashMap<>(profiles);
        copy.put(level.value(), profile);
        return new NoteLevelPolicyResolver(copy);
    }
}
