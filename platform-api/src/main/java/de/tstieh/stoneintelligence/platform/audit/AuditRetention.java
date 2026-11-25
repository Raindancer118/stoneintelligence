package de.tstieh.stoneintelligence.platform.audit;

import java.util.Map;

/** Regeln der Aufbewahrungsfrist des Protokolls (ADR 0015). */
public final class AuditRetention {

    public static final int MINIMUM_DAYS = 365;

    private AuditRetention() {
    }

    /** {@code null} = unbegrenzt aufbewahren. */
    public static void validate(Integer days) {
        if (days != null && days < MINIMUM_DAYS) {
            throw new IllegalArgumentException("retention must be at least " + MINIMUM_DAYS + " days");
        }
    }

    /**
     * Was keine Frist loescht: alles von KI-Agenten und alles, was zu einem KI-Aenderungssatz gehoert. Muss
     * zur WHERE-Klausel in {@code JdbcAuditRetentionRepository.deleteExpired} passen.
     */
    public static boolean isKept(String actor, String action, Map<String, Object> payload) {
        return actor.startsWith("ki:") || action.startsWith("ai.") || payload.containsKey("changeSet");
    }
}
