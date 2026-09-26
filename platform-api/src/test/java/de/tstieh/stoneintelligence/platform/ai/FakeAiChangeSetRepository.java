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

    private final java.util.Set<java.util.List<Object>> pairs = new java.util.HashSet<>();

    @Override
    public synchronized java.util.Set<de.tstieh.stoneintelligence.domain.id.NoteId> linkedTargets(
            de.tstieh.stoneintelligence.domain.id.VaultId vaultId, de.tstieh.stoneintelligence.domain.id.NoteId source) {
        return pairs.stream().filter(pair -> pair.get(0).equals(vaultId) && pair.get(1).equals(source))
            .map(pair -> (de.tstieh.stoneintelligence.domain.id.NoteId) pair.get(2)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized void rememberLink(de.tstieh.stoneintelligence.domain.id.VaultId vaultId,
                                          de.tstieh.stoneintelligence.domain.id.NoteId source,
                                          de.tstieh.stoneintelligence.domain.id.NoteId target, java.time.Instant at) {
        pairs.add(java.util.List.of(vaultId, source, target));
    }

    private final java.util.Map<java.util.List<Object>, java.util.List<String>> rejections = new java.util.HashMap<>();
    private final java.util.Map<java.util.List<Object>, LinkRelation> relations = new java.util.HashMap<>();

    @Override
    public synchronized java.util.Map<de.tstieh.stoneintelligence.domain.id.NoteId, java.util.List<String>> rejectedTargets(
            de.tstieh.stoneintelligence.domain.id.VaultId vaultId, de.tstieh.stoneintelligence.domain.id.NoteId source) {
        var result = new java.util.HashMap<de.tstieh.stoneintelligence.domain.id.NoteId, java.util.List<String>>();
        rejections.forEach((key, hashes) -> {
            if (key.get(0).equals(vaultId) && key.get(1).equals(source)) {
                result.put((de.tstieh.stoneintelligence.domain.id.NoteId) key.get(2), hashes);
            }
        });
        return result;
    }

    @Override
    public synchronized void rememberRejection(de.tstieh.stoneintelligence.domain.id.VaultId vaultId,
                                               de.tstieh.stoneintelligence.domain.id.NoteId source,
                                               de.tstieh.stoneintelligence.domain.id.NoteId target, String sourceHash, String targetHash,
                                               java.time.Instant at) {
        rejections.put(java.util.List.of(vaultId, source, target), java.util.List.of(sourceHash, targetHash));
    }

    @Override
    public synchronized void rememberRelation(de.tstieh.stoneintelligence.domain.id.VaultId vaultId,
                                              de.tstieh.stoneintelligence.domain.id.NoteId source,
                                              de.tstieh.stoneintelligence.domain.id.NoteId target, LinkRelation relation,
                                              java.util.UUID changeSetId, java.time.Instant at) {
        relations.put(java.util.List.of(vaultId, source, target), relation);
    }

    @Override
    public synchronized java.util.Map<de.tstieh.stoneintelligence.domain.id.NoteId, LinkRelation> relationsFrom(
            de.tstieh.stoneintelligence.domain.id.VaultId vaultId, de.tstieh.stoneintelligence.domain.id.NoteId source) {
        var result = new java.util.HashMap<de.tstieh.stoneintelligence.domain.id.NoteId, LinkRelation>();
        relations.forEach((key, relation) -> {
            if (key.get(0).equals(vaultId) && key.get(1).equals(source)) {
                result.put((de.tstieh.stoneintelligence.domain.id.NoteId) key.get(2), relation);
            }
        });
        return result;
    }
}
