package de.tstieh.stoneintelligence.platform.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemeinsamer Vertrag fuer Fake- und Postgres-Implementierung der KI-Job-Queue. */
public abstract class AiJobRepositoryContractTest {

    protected abstract AiJobRepository repository();

    /** Liefert einen existierenden Vault (Postgres braucht die Fremdschluessel-Zeile). */
    protected abstract VaultId existingVault();

    private AiJobRepository jobs;
    private VaultId vaultId;
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");
    private final Duration lease = Duration.ofMinutes(10);

    @BeforeEach
    void setUp() {
        jobs = repository();
        vaultId = existingVault();
    }

    private AiJob upload(String name, Instant at) {
        return jobs.create(new NewAiJob(vaultId, "gemini", "tom", name, "application/pdf", 2, new byte[] {1, 2, 3}, 3), at);
    }

    @Test
    void should_keepTheUpload_andScopeItByVault() {
        var job = upload("Vorlesung.pdf", now);

        var found = jobs.find(vaultId, job.id()).orElseThrow();

        assertThat(found.fileName()).isEqualTo("Vorlesung.pdf");
        assertThat(found.size()).isEqualTo(3);
        assertThat(found.level()).isEqualTo(2);
        assertThat(found.status()).isEqualTo(AiJob.Status.PENDING);
        assertThat(found.createdAt()).isEqualTo(now);
        assertThat(jobs.content(job.id())).contains(new byte[] {1, 2, 3});
        assertThat(jobs.find(existingVault(), job.id())).isEmpty();
    }

    @Test
    void should_handOutEachJobOnlyOnce_oldestFirst() {
        var older = upload("a.pdf", now);
        var newer = upload("b.pdf", now.plusSeconds(1));

        var first = jobs.claim(now.plusSeconds(5), lease).orElseThrow();
        var second = jobs.claim(now.plusSeconds(5), lease).orElseThrow();

        assertThat(first.id()).isEqualTo(older.id());
        assertThat(first.status()).isEqualTo(AiJob.Status.RUNNING);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.leaseUntil()).isEqualTo(now.plusSeconds(5).plus(lease));
        assertThat(second.id()).isEqualTo(newer.id());
        assertThat(jobs.claim(now.plusSeconds(5), lease)).isEmpty();
    }

    // Ein abgestuerzter Worker darf einen Job nicht fuer immer blockieren.
    @Test
    void should_handOutAJobAgain_whenItsLeaseRanOut() {
        var job = upload("a.pdf", now);
        jobs.claim(now, lease);

        assertThat(jobs.claim(now.plus(lease).minusSeconds(1), lease)).isEmpty();
        assertThat(jobs.claim(now.plus(lease).plusSeconds(1), lease)).get().extracting(AiJob::id, AiJob::attempts)
            .containsExactly(job.id(), 2);
    }

    @Test
    void should_giveUp_afterTheLastAttemptRanOut() {
        var job = upload("a.pdf", now);
        var at = now;
        for (var attempt = 0; attempt < 3; attempt++) {
            assertThat(jobs.claim(at, lease)).isPresent();
            at = at.plus(lease).plusSeconds(1);
        }

        assertThat(jobs.claim(at, lease)).isEmpty();
        var failed = jobs.find(vaultId, job.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(AiJob.Status.FAILED);
        assertThat(failed.error()).isNotBlank();
        assertThat(jobs.content(job.id())).isEmpty();
    }

    @Test
    void should_trackProgress_andExtendTheLease_onlyWhileRunning() {
        var job = upload("a.pdf", now);
        assertThat(jobs.progress(job.id(), "Lese Seite 1", 10, now.plus(lease))).isFalse();
        jobs.claim(now, lease);

        assertThat(jobs.progress(job.id(), "Lese Seite 3", 30, now.plus(Duration.ofMinutes(20)))).isTrue();

        var running = jobs.find(vaultId, job.id()).orElseThrow();
        assertThat(running.progress()).isEqualTo("Lese Seite 3");
        assertThat(running.percent()).isEqualTo(30);
        assertThat(running.leaseUntil()).isEqualTo(now.plus(Duration.ofMinutes(20)));
    }

    // Der Heartbeat meldet nur die Lease-Verlaengerung, ohne einen bekannten Prozentsatz - er
    // darf den zuletzt gemeldeten Fortschritt nicht loeschen.
    @Test
    void should_keepTheLastPercent_whenAHeartbeatReportsNone() {
        var job = upload("a.pdf", now);
        jobs.claim(now, lease);
        jobs.progress(job.id(), "Lese Seite 3", 30, now.plus(lease));

        jobs.progress(job.id(), "Wird verarbeitet", null, now.plus(Duration.ofMinutes(20)));

        var running = jobs.find(vaultId, job.id()).orElseThrow();
        assertThat(running.progress()).isEqualTo("Wird verarbeitet");
        assertThat(running.percent()).isEqualTo(30);
    }

    // Datensparsamkeit: das hochgeladene Dokument wird nur so lange aufbewahrt, wie es gebraucht wird.
    @Test
    void should_dropTheDocument_whenFinished() {
        var job = upload("a.pdf", now);
        var changeSet = UUID.randomUUID();
        jobs.claim(now, lease);
        jobs.attachChangeSet(job.id(), changeSet);

        assertThat(jobs.finish(job.id(), AiJob.Status.SUCCEEDED, null, now.plusSeconds(60))).isTrue();
        assertThat(jobs.finish(job.id(), AiJob.Status.FAILED, "zu spaet", now.plusSeconds(61))).isFalse();

        var done = jobs.find(vaultId, job.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(AiJob.Status.SUCCEEDED);
        assertThat(done.changeSetId()).isEqualTo(changeSet);
        assertThat(done.finishedAt()).isEqualTo(now.plusSeconds(60));
        assertThat(jobs.content(job.id())).isEmpty();
    }

    @Test
    void should_retryLater_keepingTheDocument() {
        var job = upload("a.pdf", now);
        jobs.claim(now, lease);

        assertThat(jobs.retryLater(job.id(), "Anbieter ueberlastet", now.plusSeconds(120))).isTrue();

        assertThat(jobs.claim(now.plusSeconds(60), lease)).isEmpty();
        assertThat(jobs.claim(now.plusSeconds(121), lease)).isPresent();
        assertThat(jobs.content(job.id())).isPresent();
    }

    @Test
    void should_waitForCapacity_withoutUsingUpAnAttempt() {
        var job = upload("a.pdf", now);
        jobs.claim(now, lease);

        assertThat(jobs.waitForCapacity(job.id(), "Kontingent aufgebraucht", now.plusSeconds(3600))).isTrue();

        var waiting = jobs.find(vaultId, job.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo(AiJob.Status.PENDING);
        assertThat(waiting.attempts()).isZero();
        assertThat(waiting.waitingForCapacity()).isTrue();
        assertThat(waiting.error()).isEqualTo("Kontingent aufgebraucht");
        assertThat(jobs.content(job.id())).isPresent();
        assertThat(jobs.claim(now.plusSeconds(3599), lease)).isEmpty();
        var again = jobs.claim(now.plusSeconds(3600), lease).orElseThrow();
        assertThat(again.attempts()).isEqualTo(1);
        assertThat(again.waitingForCapacity()).isFalse();
        assertThat(jobs.waitForCapacity(UUID.randomUUID(), "x", now)).isFalse();
    }

    @Test
    void should_cancelWaitingAndRunningJobs_onlyOfTheOwnVault() {
        var running = upload("a.pdf", now);
        var waiting = upload("b.pdf", now.plusSeconds(1));
        assertThat(jobs.claim(now.plusSeconds(2), lease)).get().extracting(AiJob::id).isEqualTo(running.id());

        assertThat(jobs.cancel(existingVault(), waiting.id(), now)).isFalse();
        assertThat(jobs.find(vaultId, waiting.id()).orElseThrow().status()).isEqualTo(AiJob.Status.PENDING);

        assertThat(jobs.cancel(vaultId, running.id(), now.plusSeconds(3))).isTrue();
        var cancelled = jobs.find(vaultId, running.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(AiJob.Status.CANCELLED);
        assertThat(cancelled.leaseUntil()).isNull();
        assertThat(jobs.content(running.id())).isEmpty();
        assertThat(jobs.progress(running.id(), "weiter", 50, now.plus(lease))).isFalse();
        assertThat(jobs.cancel(vaultId, running.id(), now.plusSeconds(4))).isFalse();
    }

    @Test
    void should_cancelAPendingJob_andDropItsDocument() {
        var job = upload("a.pdf", now);

        assertThat(jobs.cancel(vaultId, job.id(), now.plusSeconds(5))).isTrue();

        assertThat(jobs.find(vaultId, job.id()).orElseThrow().status()).isEqualTo(AiJob.Status.CANCELLED);
        assertThat(jobs.content(job.id())).isEmpty();
        assertThat(jobs.claim(now.plusSeconds(6), lease)).isEmpty();
    }

    @Test
    void should_countOpenJobs_andListNewestFirst() {
        var first = upload("a.pdf", now);
        var second = upload("b.pdf", now.plusSeconds(1));
        jobs.claim(now.plusSeconds(2), lease);
        jobs.finish(first.id(), AiJob.Status.SUCCEEDED, null, now.plusSeconds(3));

        assertThat(jobs.countOpen(vaultId)).isEqualTo(1);
        assertThat(jobs.list(vaultId, 10)).extracting(AiJob::id).containsExactly(second.id(), first.id());
    }

    @Test
    void should_purgeOldJobs() {
        var old = upload("alt.pdf", now.minus(Duration.ofDays(91)));
        var recent = upload("neu.pdf", now);

        assertThat(jobs.purgeCreatedBefore(now.minus(Duration.ofDays(90)))).isEqualTo(1);

        assertThat(jobs.find(vaultId, old.id())).isEmpty();
        assertThat(jobs.find(vaultId, recent.id())).isPresent();
    }
}
