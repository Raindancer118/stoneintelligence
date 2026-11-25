package de.tstieh.stoneintelligence.platform.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Speicher-Variante mit eigenen Ereignissen; loescht nach derselben Regel ({@link AuditRetention#isKept}) wie das SQL. */
class FakeAuditRetentionRepository implements AuditRetentionRepository {

    private final Map<VaultId, Integer> periods = new HashMap<>();
    private final List<AuditEvent> events = new ArrayList<>();

    void seed(VaultId vaultId, String actor, String action, Map<String, Object> payload, Instant at) {
        events.add(new AuditEvent(UUID.randomUUID(), vaultId, null, actor, action, payload, at));
    }

    List<String> remainingActions(VaultId vaultId) {
        return events.stream().filter(e -> e.vaultId().equals(vaultId)).map(AuditEvent::action).toList();
    }

    @Override
    public Integer retentionDays(VaultId vaultId) {
        return periods.get(vaultId);
    }

    @Override
    public void setRetentionDays(VaultId vaultId, Integer days) {
        if (days == null) {
            periods.remove(vaultId);
        } else {
            periods.put(vaultId, days);
        }
    }

    @Override
    public Map<VaultId, Integer> allRetentions() {
        return Map.copyOf(periods);
    }

    @Override
    public int deleteExpired(VaultId vaultId, Instant before) {
        var sizeBefore = events.size();
        events.removeIf(e -> e.vaultId().equals(vaultId) && e.occurredAt().isBefore(before)
            && !AuditRetention.isKept(e.actor(), e.action(), e.payload()));
        return sizeBefore - events.size();
    }
}
