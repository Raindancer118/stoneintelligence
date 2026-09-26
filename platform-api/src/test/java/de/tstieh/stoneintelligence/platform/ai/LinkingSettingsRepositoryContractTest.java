package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vertrag fuer {@code FakeLinkingSettingsRepository} und {@code JdbcLinkingSettingsRepository}. */
public abstract class LinkingSettingsRepositoryContractTest {

    protected abstract LinkingSettingsRepository repository();

    protected abstract VaultId newVault();

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @Test
    void should_storeAndReplaceTheSettings_ofAVault() {
        var repository = repository();
        var vault = newVault();
        assertThat(repository.find(vault)).isEmpty();

        repository.save(LinkingSettings.defaults(vault, "tom", NOW));
        repository.save(new LinkingSettings(vault, true, LinkingSettings.Mode.SEMANTIC, false, 5, "gateway", "anna", null, NOW.plusSeconds(1)));

        assertThat(repository.find(vault)).get().satisfies(settings -> {
            assertThat(settings.enabled()).isTrue();
            assertThat(settings.mode()).isEqualTo(LinkingSettings.Mode.SEMANTIC);
            assertThat(settings.linkHumanNotes()).isFalse();
            assertThat(settings.maxLinksPerNote()).isEqualTo(5);
            assertThat(settings.service()).isEqualTo("gateway");
            assertThat(settings.requestedBy()).isEqualTo("anna");
        });
    }

    @Test
    void should_listOnlyEnabledVaults_andRememberTheLastRun() {
        var repository = repository();
        var on = newVault();
        var off = newVault();
        repository.save(new LinkingSettings(on, true, LinkingSettings.Mode.AI, true, null, null, "tom", null, NOW));
        repository.save(LinkingSettings.defaults(off, "tom", NOW));

        repository.markRun(on, NOW.plusSeconds(60));

        assertThat(repository.enabled()).extracting(LinkingSettings::vaultId).contains(on).doesNotContain(off);
        assertThat(repository.find(on)).get().extracting(LinkingSettings::lastRunAt).isEqualTo(NOW.plusSeconds(60));
        repository.save(new LinkingSettings(on, true, LinkingSettings.Mode.AI, true, 3, null, "tom", null, NOW));
        assertThat(repository.find(on)).get().extracting(LinkingSettings::lastRunAt).isEqualTo(NOW.plusSeconds(60));
    }
}
