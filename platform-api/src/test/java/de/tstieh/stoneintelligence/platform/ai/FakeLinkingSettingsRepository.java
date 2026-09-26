package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public final class FakeLinkingSettingsRepository implements LinkingSettingsRepository {

    private final Map<VaultId, LinkingSettings> settings = new ConcurrentHashMap<>();

    @Override
    public Optional<LinkingSettings> find(VaultId vaultId) {
        return Optional.ofNullable(settings.get(vaultId));
    }

    @Override
    public void save(LinkingSettings next) {
        var lastRun = find(next.vaultId()).map(LinkingSettings::lastRunAt).orElse(null);
        settings.put(next.vaultId(), new LinkingSettings(next.vaultId(), next.enabled(), next.mode(), next.linkHumanNotes(),
            next.maxLinksPerNote(), next.service(), next.requestedBy(), lastRun, next.updatedAt()));
    }

    @Override
    public List<LinkingSettings> enabled() {
        return settings.values().stream().filter(LinkingSettings::enabled)
            .sorted(java.util.Comparator.comparing(s -> s.vaultId().value())).toList();
    }

    @Override
    public void markRun(VaultId vaultId, Instant at) {
        settings.computeIfPresent(vaultId, (id, s) -> new LinkingSettings(id, s.enabled(), s.mode(), s.linkHumanNotes(),
            s.maxLinksPerNote(), s.service(), s.requestedBy(), at, s.updatedAt()));
    }
}
