package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public final class FakeAiChangeSetRepository implements AiChangeSetRepository {

    private final Map<UUID, AiChangeSet> sets = new ConcurrentHashMap<>();
    private final List<AiChange> changes = new ArrayList<>();

    @Override
    public synchronized AiChangeSet create(VaultId vaultId, String service, String agent, String requestedBy, String label, Instant at) {
        var set = new AiChangeSet(UUID.randomUUID(), vaultId, service, agent, requestedBy, label, at, null);
        sets.put(set.id(), set);
        return set;
    }

    @Override
    public Optional<AiChangeSet> find(VaultId vaultId, UUID id) {
        return Optional.ofNullable(sets.get(id)).filter(set -> set.vaultId().equals(vaultId));
    }

    @Override
    public List<AiChangeSet> list(VaultId vaultId, int limit) {
        return sets.values().stream().filter(set -> set.vaultId().equals(vaultId))
            .sorted(Comparator.comparing(AiChangeSet::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized void addChange(AiChange change) {
        changes.add(change);
    }

    @Override
    public synchronized List<AiChange> changes(UUID changeSetId) {
        return changes.stream().filter(change -> change.changeSetId().equals(changeSetId)).toList();
    }

    @Override
    public synchronized boolean markReverted(UUID id, Instant at) {
        var set = sets.get(id);
        if (set == null || set.revertedAt() != null) {
            return false;
        }
        sets.put(id, new AiChangeSet(set.id(), set.vaultId(), set.service(), set.agent(), set.requestedBy(), set.label(), set.createdAt(), at));
        return true;
    }

    @Override
    public synchronized int purgeCreatedBefore(Instant cutoff) {
        var old = sets.values().stream().filter(set -> set.createdAt().isBefore(cutoff)).map(AiChangeSet::id).toList();
        old.forEach(sets::remove);
        changes.removeIf(change -> old.contains(change.changeSetId()));
        return old.size();
    }
}
