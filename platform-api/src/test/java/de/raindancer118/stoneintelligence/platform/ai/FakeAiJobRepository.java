package de.raindancer118.stoneintelligence.platform.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

public final class FakeAiJobRepository implements AiJobRepository {

    private final Map<UUID, AiJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, byte[]> contents = new LinkedHashMap<>();

    private AiJob with(AiJob j, AiJob.Status status, int attempts, Instant availableAt, Instant leaseUntil, String progress,
                       Integer percent, String error, UUID changeSetId, Instant finishedAt) {
        return with(j, status, attempts, availableAt, leaseUntil, progress, percent, error, changeSetId, finishedAt,
            j.waitingForCapacity() && status == AiJob.Status.PENDING);
    }

    private AiJob with(AiJob j, AiJob.Status status, int attempts, Instant availableAt, Instant leaseUntil, String progress,
                       Integer percent, String error, UUID changeSetId, Instant finishedAt, boolean waitingForCapacity) {
        var updated = new AiJob(j.id(), j.vaultId(), j.service(), j.requestedBy(), j.fileName(), j.contentType(), j.size(), j.level(),
            status, attempts, j.maxAttempts(), availableAt, leaseUntil, progress, percent, error, changeSetId, j.createdAt(), finishedAt,
            waitingForCapacity);
        jobs.put(j.id(), updated);
        return updated;
    }

    @Override
    public synchronized AiJob create(NewAiJob job, Instant at) {
        var created = new AiJob(UUID.randomUUID(), job.vaultId(), job.service(), job.requestedBy(), job.fileName(), job.contentType(),
            job.content().length, job.level(), AiJob.Status.PENDING, 0, job.maxAttempts(), at, null, null, null, null, null, at, null, false);
        jobs.put(created.id(), created);
        contents.put(created.id(), job.content().clone());
        return created;
    }

    @Override
    public synchronized Optional<AiJob> find(VaultId vaultId, UUID id) {
        return findById(id).filter(job -> job.vaultId().equals(vaultId));
    }

    @Override
    public synchronized Optional<AiJob> findById(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public synchronized List<AiJob> list(VaultId vaultId, int limit) {
        return jobs.values().stream().filter(job -> job.vaultId().equals(vaultId))
            .sorted(Comparator.comparing(AiJob::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized int countOpen(VaultId vaultId) {
        return (int) jobs.values().stream().filter(job -> job.vaultId().equals(vaultId) && job.open()).count();
    }

    @Override
    public synchronized Optional<AiJob> claim(Instant now, Duration lease) {
        for (var job : List.copyOf(jobs.values())) {
            if (job.status() == AiJob.Status.RUNNING && job.leaseUntil().isBefore(now) && job.attempts() >= job.maxAttempts()) {
                with(job, AiJob.Status.FAILED, job.attempts(), job.availableAt(), job.leaseUntil(), job.progress(), job.percent(),
                    "Abgebrochen: die Verarbeitung ist " + job.attempts() + "-mal nicht fertig geworden", job.changeSetId(), now);
                contents.remove(job.id());
            }
        }
        return jobs.values().stream()
            .filter(job -> job.attempts() < job.maxAttempts())
            .filter(job -> job.status() == AiJob.Status.PENDING && !job.availableAt().isAfter(now)
                || job.status() == AiJob.Status.RUNNING && job.leaseUntil().isBefore(now))
            .min(Comparator.comparing(AiJob::createdAt))
            .map(job -> with(job, AiJob.Status.RUNNING, job.attempts() + 1, job.availableAt(), now.plus(lease), job.progress(),
                job.percent(), job.error(), job.changeSetId(), null, false));
    }

    @Override
    public synchronized Optional<byte[]> content(UUID id) {
        return Optional.ofNullable(contents.get(id)).map(byte[]::clone);
    }

    @Override
    public synchronized void attachChangeSet(UUID id, UUID changeSetId) {
        var job = jobs.get(id);
        with(job, job.status(), job.attempts(), job.availableAt(), job.leaseUntil(), job.progress(), job.percent(), job.error(),
            changeSetId, job.finishedAt());
    }

    @Override
    public synchronized boolean progress(UUID id, String message, Integer percent, Instant leaseUntil) {
        var job = jobs.get(id);
        if (job == null || job.status() != AiJob.Status.RUNNING) {
            return false;
        }
        with(job, job.status(), job.attempts(), job.availableAt(), leaseUntil, message,
            percent != null ? percent : job.percent(), job.error(), job.changeSetId(), null);
        return true;
    }

    @Override
    public synchronized boolean finish(UUID id, AiJob.Status status, String error, Instant at) {
        var job = jobs.get(id);
        if (job == null || job.status() != AiJob.Status.RUNNING) {
            return false;
        }
        with(job, status, job.attempts(), job.availableAt(), null, job.progress(), job.percent(), error, job.changeSetId(), at);
        contents.remove(id);
        return true;
    }

    @Override
    public synchronized boolean retryLater(UUID id, String error, Instant availableAt) {
        var job = jobs.get(id);
        if (job == null || job.status() != AiJob.Status.RUNNING) {
            return false;
        }
        with(job, AiJob.Status.PENDING, job.attempts(), availableAt, null, job.progress(), job.percent(), error, job.changeSetId(), null,
            false);
        return true;
    }

    @Override
    public synchronized boolean waitForCapacity(UUID id, String error, Instant availableAt) {
        var job = jobs.get(id);
        if (job == null || job.status() != AiJob.Status.RUNNING) {
            return false;
        }
        with(job, AiJob.Status.PENDING, Math.max(job.attempts() - 1, 0), availableAt, null, job.progress(), job.percent(), error,
            job.changeSetId(), null, true);
        return true;
    }

    @Override
    public synchronized boolean cancel(VaultId vaultId, UUID id, Instant at) {
        var job = jobs.get(id);
        if (job == null || !job.vaultId().equals(vaultId) || !job.open()) {
            return false;
        }
        with(job, AiJob.Status.CANCELLED, job.attempts(), job.availableAt(), null, job.progress(), job.percent(), job.error(),
            job.changeSetId(), at);
        contents.remove(id);
        return true;
    }

    @Override
    public synchronized int purgeCreatedBefore(Instant cutoff) {
        var old = jobs.values().stream().filter(job -> job.createdAt().isBefore(cutoff)).map(AiJob::id).toList();
        old.forEach(id -> {
            jobs.remove(id);
            contents.remove(id);
        });
        return old.size();
    }
}
