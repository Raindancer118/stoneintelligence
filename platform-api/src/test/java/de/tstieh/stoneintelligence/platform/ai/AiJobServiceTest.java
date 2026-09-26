package de.tstieh.stoneintelligence.platform.ai;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiJobServiceTest {

    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
    private final VaultId vaultId = VaultId.newId();
    private final FakeAiJobRepository jobs = new FakeAiJobRepository();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-23T10:00:00Z"));
    private final List<String> startedChangeSets = new ArrayList<>();
    private AiServiceDirectory services = new AiServiceDirectory(List.of(
        new AiService("gemini", "Gemini", Set.of(1)), new AiService("lokal", "Ollama lokal", Set.of(1, 2))));
    private final List<String> revertedChangeSets = new ArrayList<>();
    private final AiJobService service = new AiJobService(jobs, () -> services, (vault, aiService, requestedBy, label) -> {
        startedChangeSets.add(aiService.id() + ":" + requestedBy + ":" + label);
        return UUID.randomUUID();
    }, (vault, changeSetId, actor) -> revertedChangeSets.add(changeSetId + ":" + actor), now::get);

    private static AiJobService.Upload pdf(String name) {
        return new AiJobService.Upload(name, "application/pdf", PDF);
    }

    @Nested
    class Hochladen {

        @Test
        void should_createOneJobPerFile() {
            var created = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"),
                new AiJobService.Upload("Notizen.md", "text/markdown", "# Hallo\n".getBytes(StandardCharsets.UTF_8))));

            assertThat(created).extracting(AiJob::fileName, AiJob::contentType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a.pdf", "application/pdf"),
                    org.assertj.core.groups.Tuple.tuple("Notizen.md", "text/markdown"));
            assertThat(created).allSatisfy(job -> assertThat(job.requestedBy()).isEqualTo("tom"));
        }

        // Toms Vorgabe: je Dienst einstellbar, welche Levels er verarbeiten darf - das gilt schon beim Hochladen.
        @Test
        void should_refuseLevelsTheServiceMayNotProcess() {
            assertThatThrownBy(() -> service.upload(vaultId, "tom", "gemini", 2, List.of(pdf("intern.pdf"))))
                .isInstanceOf(AiWriteRefusedException.class).hasMessageContaining("Level 2");
            assertThat(service.upload(vaultId, "tom", "lokal", 2, List.of(pdf("intern.pdf")))).hasSize(1);
            assertThatThrownBy(() -> service.upload(vaultId, "tom", "gibtsnicht", 1, List.of(pdf("a.pdf"))))
                .isInstanceOf(AiWriteRefusedException.class);
        }

        // Nur, was die Pipeline lesen kann - erkannt am Inhalt, nicht an der Endung.
        @Test
        void should_acceptOnlyReadableDocuments() {
            for (var upload : List.of(
                    new AiJobService.Upload("tarnung.pdf", "application/pdf", new byte[] {0x4d, 0x5a, 0, 0}),
                    new AiJobService.Upload("bild.png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47}),
                    new AiJobService.Upload("binaer.txt", "text/plain", new byte[] {'a', 0, 'b'}),
                    new AiJobService.Upload("leer.pdf", "application/pdf", new byte[0]))) {
                assertThatThrownBy(() -> service.upload(vaultId, "tom", "gemini", 1, List.of(upload)), upload.fileName())
                    .isInstanceOf(AiWriteRefusedException.class);
            }
        }

        @Test
        void should_limitSizeAndNumber() {
            var tooBig = new byte[AiJobService.MAX_FILE_BYTES + 1];
            System.arraycopy(PDF, 0, tooBig, 0, PDF.length);
            assertThatThrownBy(() -> service.upload(vaultId, "tom", "gemini", 1, List.of(new AiJobService.Upload("x.pdf", "application/pdf", tooBig))))
                .isInstanceOf(AiWriteRefusedException.class);
            var many = java.util.stream.IntStream.range(0, AiJobService.MAX_FILES_PER_UPLOAD + 1).mapToObj(i -> pdf(i + ".pdf")).toList();
            assertThatThrownBy(() -> service.upload(vaultId, "tom", "gemini", 1, many)).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> service.upload(vaultId, "tom", "gemini", 1, List.of())).isInstanceOf(AiWriteRefusedException.class);
        }

        // Gescannte Lehrbuecher sind oft 30-80 MB - die alte Grenze von 20 MB wies sie ab.
        @Test
        void should_acceptABookOfSeveralHundredPages() {
            var book = new byte[80 * 1024 * 1024];
            System.arraycopy(PDF, 0, book, 0, PDF.length);

            assertThat(service.upload(vaultId, "tom", "gemini", 1, List.of(new AiJobService.Upload("Lehrbuch.pdf", "application/pdf", book))))
                .hasSize(1);
            assertThat(AiJobService.MAX_FILE_BYTES).isEqualTo(100 * 1024 * 1024);
        }

        // Backpressure: ein Vault kann die Warteschlange nicht beliebig fuellen.
        @Test
        void should_limitOpenJobsPerVault() {
            for (var i = 0; i < AiJobService.MAX_OPEN_JOBS_PER_VAULT; i++) {
                service.upload(vaultId, "tom", "gemini", 1, List.of(pdf(i + ".pdf")));
            }

            assertThatThrownBy(() -> service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("zuviel.pdf"))))
                .isInstanceOf(AiWriteRefusedException.class);
            assertThat(service.upload(VaultId.newId(), "tom", "gemini", 1, List.of(pdf("anderer.pdf")))).hasSize(1);
        }

        @Test
        void should_keepOnlyASafeFileName() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("C:\\Users\\tom\\../Vorlesung\u0007 1.pdf"))).getFirst();

            assertThat(job.fileName()).isEqualTo("Vorlesung 1.pdf");
        }
    }

    // ADR 0012: ein Verlinkungslauf ist ein Job ohne Dokument, hoechstens einer je Vault zugleich.
    @Nested
    class Verlinkung {

        @Test
        void should_queueALinkingRun_thatTheWorkerClaimsLikeAnyJob() {
            var run = service.startLinking(vaultId, "tom", "lokal");

            assertThat(run.kind()).isEqualTo(AiJob.Kind.LINKING);
            assertThat(run.fileName()).isEqualTo("Verlinkung");
            var claimed = service.claim().orElseThrow();
            assertThat(claimed.kind()).isEqualTo(AiJob.Kind.LINKING);
            assertThat(startedChangeSets).containsExactly("lokal:tom:Verlinkung");
            assertThatThrownBy(() -> service.document(run.id())).isInstanceOf(AiWriteRefusedException.class);
        }

        @Test
        void should_runAtMostOneLinkingRunPerVault() {
            service.startLinking(vaultId, "tom", "lokal");

            assertThatThrownBy(() -> service.startLinking(vaultId, "anna", "lokal")).isInstanceOf(AiWriteRefusedException.class);
            service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf")));
            assertThat(service.startLinking(VaultId.newId(), "tom", "lokal").kind()).isEqualTo(AiJob.Kind.LINKING);
        }

        @Test
        void should_refuseUnknownServices() {
            assertThatThrownBy(() -> service.startLinking(vaultId, "tom", "gibtsnicht")).isInstanceOf(AiWriteRefusedException.class);
        }
    }

    @Nested
    class Verarbeiten {

        @Test
        void should_startAChangeSet_whenAJobIsClaimed_onlyOnce() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("Vorlesung.pdf"))).getFirst();

            var claimed = service.claim().orElseThrow();
            assertThat(claimed.id()).isEqualTo(job.id());
            assertThat(claimed.changeSetId()).isNotNull();
            assertThat(startedChangeSets).containsExactly("gemini:tom:Vorlesung.pdf");

            // Worker abgestuerzt, Lease abgelaufen: derselbe Job, dasselbe Change-Set.
            now.set(now.get().plus(AiJobService.LEASE).plusSeconds(1));
            var again = service.claim().orElseThrow();
            assertThat(again.changeSetId()).isEqualTo(claimed.changeSetId());
            assertThat(startedChangeSets).hasSize(1);
        }

        // Wird ein Dienst aus der Konfiguration genommen, verarbeitet ihn niemand mehr heimlich weiter.
        @Test
        void should_failJobsOfServicesThatAreNoLongerConfigured() {
            var orphan = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            var fine = service.upload(vaultId, "tom", "lokal", 1, List.of(pdf("b.pdf"))).getFirst();
            services = new AiServiceDirectory(List.of(new AiService("lokal", "Ollama lokal", Set.of(1))));

            assertThat(service.claim()).get().extracting(AiJob::id).isEqualTo(fine.id());
            assertThat(jobs.find(vaultId, orphan.id()).orElseThrow().status()).isEqualTo(AiJob.Status.FAILED);
        }

        @Test
        void should_handOutTheDocument_onlyWhileRunning() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            assertThatThrownBy(() -> service.document(job.id())).isInstanceOf(AiWriteRefusedException.class);

            service.claim();

            assertThat(service.document(job.id())).isEqualTo(PDF);
            service.complete(job.id());
            assertThatThrownBy(() -> service.document(job.id())).isInstanceOf(AiWriteRefusedException.class);
        }

        @Test
        void should_retryTemporaryProblems_withGrowingPauses_thenGiveUp() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            service.claim();

            service.fail(job.id(), "Anbieter ueberlastet", true);
            var waiting = jobs.find(vaultId, job.id()).orElseThrow();
            assertThat(waiting.status()).isEqualTo(AiJob.Status.PENDING);
            assertThat(waiting.availableAt()).isEqualTo(now.get().plus(Duration.ofMinutes(1)));

            now.set(waiting.availableAt());
            service.claim();
            service.fail(job.id(), "Anbieter ueberlastet", true);
            assertThat(jobs.find(vaultId, job.id()).orElseThrow().availableAt()).isEqualTo(now.get().plus(Duration.ofMinutes(2)));

            now.set(now.get().plus(Duration.ofMinutes(2)));
            service.claim();
            service.fail(job.id(), "Anbieter ueberlastet", true);
            assertThat(jobs.find(vaultId, job.id()).orElseThrow().status()).isEqualTo(AiJob.Status.FAILED);
        }

        @Test
        void should_failImmediately_whenTheProblemIsPermanent() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            service.claim();

            service.fail(job.id(), "PDF ist verschluesselt", false);

            var failed = jobs.find(vaultId, job.id()).orElseThrow();
            assertThat(failed.status()).isEqualTo(AiJob.Status.FAILED);
            assertThat(failed.error()).isEqualTo("PDF ist verschluesselt");
        }

        @Test
        void should_reportProgress_andRefuseItForFinishedJobs() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            service.claim();

            service.progress(job.id(), "Seite 2 von 5", 40);
            assertThat(jobs.find(vaultId, job.id()).orElseThrow().leaseUntil()).isEqualTo(now.get().plus(AiJobService.LEASE));

            service.complete(job.id());
            assertThatThrownBy(() -> service.progress(job.id(), "zu spaet", 90)).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> service.progress(job.id(), "x", 101)).isInstanceOf(AiWriteRefusedException.class);
        }
    }

    @Nested
    class KapazitaetUndAbbrechen {

        @Test
        void should_waitForCapacity_untilItIsBack_withoutUsingUpAnAttempt() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            service.claim();
            var back = now.get().plus(Duration.ofHours(5));

            service.waitForCapacity(job.id(), "Kontingent von Gemini aufgebraucht", back);

            var waiting = jobs.find(vaultId, job.id()).orElseThrow();
            assertThat(waiting.status()).isEqualTo(AiJob.Status.PENDING);
            assertThat(waiting.waitingForCapacity()).isTrue();
            assertThat(waiting.attempts()).isZero();
            assertThat(waiting.availableAt()).isEqualTo(back);
            assertThat(waiting.error()).isEqualTo("Kontingent von Gemini aufgebraucht");
        }

        @Test
        void should_waitForCapacity_withinSaneBounds() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            service.claim();
            service.waitForCapacity(job.id(), "x", null);
            assertThat(jobs.find(vaultId, job.id()).orElseThrow().availableAt()).isEqualTo(now.get().plus(AiJobService.UNKNOWN_CAPACITY_WAIT));

            now.set(now.get().plus(AiJobService.UNKNOWN_CAPACITY_WAIT));
            service.claim();
            service.waitForCapacity(job.id(), "x", now.get().minusSeconds(30));
            assertThat(jobs.find(vaultId, job.id()).orElseThrow().availableAt()).isEqualTo(now.get().plus(AiJobService.MIN_CAPACITY_WAIT));

            now.set(now.get().plus(AiJobService.MIN_CAPACITY_WAIT));
            service.claim();
            service.waitForCapacity(job.id(), "x", now.get().plus(Duration.ofDays(30)));
            assertThat(jobs.find(vaultId, job.id()).orElseThrow().availableAt()).isEqualTo(now.get().plus(AiJobService.MAX_CAPACITY_WAIT));
        }

        @Test
        void should_giveUp_whenNoCapacityCameBackForDays() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            now.set(now.get().plus(AiJobService.CAPACITY_PATIENCE).plusSeconds(1));
            service.claim();

            service.waitForCapacity(job.id(), "Kontingent aufgebraucht", now.get().plusSeconds(600));

            var failed = jobs.find(vaultId, job.id()).orElseThrow();
            assertThat(failed.status()).isEqualTo(AiJob.Status.FAILED);
            assertThat(failed.error()).contains("Kontingent aufgebraucht");
        }

        @Test
        void should_cancelAWaitingJob_withoutTouchingTheVault() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();

            assertThat(service.cancel(vaultId, job.id(), "anna")).isTrue();

            assertThat(jobs.find(vaultId, job.id()).orElseThrow().status()).isEqualTo(AiJob.Status.CANCELLED);
            assertThat(revertedChangeSets).isEmpty();
        }

        @Test
        void should_cancelARunningJob_andUndoWhatItWroteSoFar() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            var claimed = service.claim().orElseThrow();

            assertThat(service.cancel(vaultId, job.id(), "anna")).isTrue();

            assertThat(jobs.find(vaultId, job.id()).orElseThrow().status()).isEqualTo(AiJob.Status.CANCELLED);
            assertThat(revertedChangeSets).containsExactly(claimed.changeSetId() + ":anna");
            assertThatThrownBy(() -> service.progress(job.id(), "weiter", 50)).isInstanceOf(AiJobGoneException.class);
            assertThatThrownBy(() -> service.document(job.id())).isInstanceOf(AiJobGoneException.class);
        }

        @Test
        void should_notCancelFinishedJobs() {
            var job = service.upload(vaultId, "tom", "gemini", 1, List.of(pdf("a.pdf"))).getFirst();
            service.claim();
            service.complete(job.id());

            assertThat(service.cancel(vaultId, job.id(), "anna")).isFalse();
            assertThat(revertedChangeSets).isEmpty();
        }
    }
}
