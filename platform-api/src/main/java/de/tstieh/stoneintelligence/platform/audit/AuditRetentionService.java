package de.tstieh.stoneintelligence.platform.audit;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Aufbewahrungsfrist des Protokolls je Vault (ADR 0015): setzen, und taeglich Abgelaufenes loeschen. */
@Service
public class AuditRetentionService {

    static final String SYSTEM_ACTOR = "system";

    private static final Logger LOG = LoggerFactory.getLogger(AuditRetentionService.class);

    private final AuditRetentionRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public AuditRetentionService(AuditRetentionRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    public Integer retentionDays(VaultId vaultId) {
        return repository.retentionDays(vaultId);
    }

    /** {@code days == null}: unbegrenzt. Die Aenderung steht selbst im Protokoll. */
    public void change(VaultId vaultId, String actor, Integer days) {
        AuditRetention.validate(days);
        var before = repository.retentionDays(vaultId);
        if (Objects.equals(before, days)) {
            return;
        }
        repository.setRetentionDays(vaultId, days);
        var payload = new LinkedHashMap<String, Object>();
        if (before != null) {
            payload.put("from", before);
        }
        if (days != null) {
            payload.put("to", days);
        }
        audit.record(vaultId, null, actor, "audit.retention-changed", payload);
    }

    /** Taeglich nachts; laufen mehrere Instanzen, loeschen sie dieselben Zeilen hoechstens doppelt - harmlos. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Europe/Berlin")
    public int purgeExpired() {
        var total = 0;
        for (var entry : repository.allRetentions().entrySet()) {
            try {
                var deleted = repository.deleteExpired(entry.getKey(), clock.instant().minus(Duration.ofDays(entry.getValue())));
                if (deleted > 0) {
                    audit.record(entry.getKey(), null, SYSTEM_ACTOR, "audit.purged",
                        Map.of("count", deleted, "retentionDays", entry.getValue()));
                    total += deleted;
                }
            } catch (RuntimeException e) {
                LOG.warn("Audit retention failed for vault {}", entry.getKey(), e);
            }
        }
        return total;
    }
}
