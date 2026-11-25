package de.tstieh.stoneintelligence.platform.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

    private final FakeAuditRetentionRepository repository = new FakeAuditRetentionRepository();
    private final HistoryControllerTest.FakeAudit audit = new HistoryControllerTest.FakeAudit();
    private final AuditRetentionService service = new AuditRetentionService(repository, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    private final VaultId vault = VaultId.newId();

    @Test
    void should_recordWhoChangedThePeriod_withOldAndNewValue() {
        service.change(vault, "tom", 730);
        service.change(vault, "tom", null);

        assertThat(service.retentionDays(vault)).isNull();
        assertThat(audit.events).extracting(AuditEvent::actor, AuditEvent::action, AuditEvent::payload)
            .containsExactly(
                Tuple.tuple("tom", "audit.retention-changed", Map.of("to", 730)),
                Tuple.tuple("tom", "audit.retention-changed", Map.of("from", 730)));
    }

    @Test
    void should_notRecordAnything_when_periodStaysTheSame() {
        service.change(vault, "tom", 365);
        service.change(vault, "tom", 365);

        assertThat(audit.events).hasSize(1);
    }

    @Test
    void should_refusePeriodsUnderOneYear() {
        assertThatThrownBy(() -> service.change(vault, "tom", 364)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.change(vault, "tom", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.retentionDays(vault)).isNull();
        assertThat(audit.events).isEmpty();
    }

    @Test
    void should_purgeExpiredEventsPerVault_andLeaveVaultsWithoutAPeriodAlone() {
        var forever = VaultId.newId();
        repository.setRetentionDays(vault, 365);
        repository.seed(vault, "tom", "expired", Map.of(), NOW.minusSeconds(366L * 86_400));
        repository.seed(vault, "tom", "fresh", Map.of(), NOW.minusSeconds(364L * 86_400));
        repository.seed(vault, "ki:agent", "agent", Map.of(), NOW.minusSeconds(900L * 86_400));
        repository.seed(forever, "tom", "ancient", Map.of(), NOW.minusSeconds(5000L * 86_400));

        var deleted = service.purgeExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.remainingActions(vault)).containsExactlyInAnyOrder("fresh", "agent");
        assertThat(repository.remainingActions(forever)).containsExactly("ancient");
        assertThat(audit.events).singleElement().satisfies(event -> {
            assertThat(event.vaultId()).isEqualTo(vault);
            assertThat(event.action()).isEqualTo("audit.purged");
            assertThat(event.payload()).containsEntry("count", 1);
        });
    }

    @Test
    void should_notRecordAPurge_when_nothingExpired() {
        repository.setRetentionDays(vault, 365);

        assertThat(service.purgeExpired()).isZero();
        assertThat(audit.events).isEmpty();
    }
}
