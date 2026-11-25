package de.tstieh.stoneintelligence.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vertrag, den {@code FakeAuditRetentionRepository} und {@code JdbcAuditRetentionRepository} beide erfuellen. */
abstract class AuditRetentionRepositoryContractTest {

    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    private static final Instant OLD = NOW.minusSeconds(400L * 86_400);
    private static final Instant RECENT = NOW.minusSeconds(30L * 86_400);

    protected abstract AuditRetentionRepository repository();

    protected abstract VaultId newVault();

    protected abstract void seed(VaultId vaultId, String actor, String action, Map<String, Object> payload, Instant at);

    protected abstract List<String> remainingActions(VaultId vaultId);

    @Test
    void should_keepForeverByDefault_andRememberAPeriod() {
        var vault = newVault();

        assertThat(repository().retentionDays(vault)).isNull();
        repository().setRetentionDays(vault, 730);
        assertThat(repository().retentionDays(vault)).isEqualTo(730);
        repository().setRetentionDays(vault, null);
        assertThat(repository().retentionDays(vault)).isNull();
    }

    @Test
    void should_listOnlyVaultsWithAPeriod() {
        var withPeriod = newVault();
        var without = newVault();
        repository().setRetentionDays(withPeriod, 365);

        assertThat(repository().allRetentions()).containsEntry(withPeriod, 365).doesNotContainKey(without);
    }

    @Test
    void should_deleteOldHumanEvents_butKeepAgentAndChangeSetEvents() {
        var vault = newVault();
        var other = newVault();
        seed(vault, "tom", "old-human", Map.of(), OLD);
        seed(vault, "tom", "recent-human", Map.of(), RECENT);
        seed(vault, "ki:linker", "old-agent", Map.of(), OLD);
        seed(vault, "tom", "old-changeset", Map.of("changeSet", "5f0c0c7e-0000-4000-8000-000000000001"), OLD);
        seed(vault, "tom", "ai.change-set-reverted", Map.of(), OLD);
        seed(other, "tom", "other-vault", Map.of(), OLD);

        var deleted = repository().deleteExpired(vault, NOW.minusSeconds(365L * 86_400));

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingActions(vault)).containsExactlyInAnyOrder("recent-human", "old-agent", "old-changeset", "ai.change-set-reverted");
        assertThat(remainingActions(other)).containsExactly("other-vault");
    }
}
