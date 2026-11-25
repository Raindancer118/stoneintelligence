package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.ai.AiJob;
import de.tstieh.stoneintelligence.platform.ai.AiJobService;
import de.tstieh.stoneintelligence.platform.ai.AiService;
import de.tstieh.stoneintelligence.platform.ai.AiServiceDirectory;
import de.tstieh.stoneintelligence.platform.ai.AiWriteRefusedException;
import de.tstieh.stoneintelligence.platform.ai.FakeAiJobRepository;
import de.tstieh.stoneintelligence.platform.ai.FakeNoteEmbeddingRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.search.FakeNoteTextIndex;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AskServiceTest {

    private static final AiService GEMINI = new AiService("gemini", "Gemini", Set.of(1));
    private static final AiService LOKAL = new AiService("lokal", "Ollama lokal", Set.of(1, 2), true);

    private final VaultId vault = VaultId.newId();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeNoteTextIndex texts = new FakeNoteTextIndex();
    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final VaultAccessGuard access = new VaultAccessGuard(authorization, grants);
    private final FakeAiJobRepository jobRepository = new FakeAiJobRepository();
    private final FakeQuestionRepository questions = new FakeQuestionRepository();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-25T08:00:00Z"));
    private final AiServiceDirectory services = new AiServiceDirectory(List.of(GEMINI, LOKAL));
    private final AiJobService jobs = new AiJobService(jobRepository, () -> services, (v, s, by, label) -> UUID.randomUUID(),
        (v, set, actor) -> { }, now::get);
    private final Set<String> consents = new HashSet<>(Set.of("tom"));
    private final List<String> audited = new ArrayList<>();
    private final List<Map<String, Object>> auditPayloads = new ArrayList<>();
    private final AskRetrieval retrieval = new AskRetrieval(texts, new FakeNoteEmbeddingRepository(notes), notes, access,
        AiService::mayProcess, 0.8);
    private final AskService ask = new AskService(questions, jobs, jobRepository, services, retrieval, access, notes, v -> Set.copyOf(consents),
        (v, note, actor, action, payload) -> {
            audited.add(actor + ":" + action + ":" + note);
            auditPayloads.add(payload);
        }, now::get);

    private final Map<String, UUID> groups = new java.util.HashMap<>();

    @BeforeEach
    void members() {
        for (var subject : List.of("ben", "anna")) {
            var role = authorization.createRole(vault, subject, Set.of(Permission.READ));
            var group = authorization.createGroup(vault, subject);
            authorization.assignRole(group.id(), role.id());
            authorization.addMember(group.id(), subject);
            groups.put(subject, group.id());
        }
    }

    private NoteId note(String path, String author, String text) {
        var note = notes.create(vault, path, NoteLevel.of(1), author);
        texts.upsert(note.id(), vault, 1, text);
        return note.id();
    }

    private AiJob claim() {
        return jobs.claim().orElseThrow();
    }

    @Nested
    class Fragen {

        @Test
        void should_queueTheQuestion_asAnAskJob_inTheNameOfThePerson() {
            var view = ask.ask(vault, "ben", "  Wo läuft die Lichtreaktion ab?  ", "gemini");

            assertThat(view.status()).isEqualTo("PENDING");
            assertThat(view.question().question()).isEqualTo("Wo läuft die Lichtreaktion ab?");
            assertThat(view.question().askedBy()).isEqualTo("ben");
            assertThat(jobRepository.findById(view.question().jobId()).orElseThrow())
                .satisfies(job -> {
                    assertThat(job.kind()).isEqualTo(AiJob.Kind.ASK);
                    assertThat(job.requestedBy()).isEqualTo("ben");
                    assertThat(job.service()).isEqualTo("gemini");
                });
        }

        @Test
        void should_preferALocalService_whenNoneIsChosen() {
            assertThat(ask.ask(vault, "ben", "Wie heißt die Hauptstadt?", null).question().service()).isEqualTo("lokal");
        }

        @Test
        void should_refuseNonMembers_tooShortOrLongQuestions_unknownServices_andTooManyOpenOnes() {
            assertThatThrownBy(() -> ask.ask(vault, "fremd", "Wie heißt die Hauptstadt?", null)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> ask.ask(vault, "ben", "Wie?", null)).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> ask.ask(vault, "ben", "x".repeat(AskService.MAX_QUESTION_LENGTH + 1), null))
                .isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> ask.ask(vault, "ben", "Wie heißt die Hauptstadt?", "gibtsnicht")).isInstanceOf(AiWriteRefusedException.class);
            for (var i = 0; i < AskService.MAX_OPEN_PER_PERSON; i++) {
                ask.ask(vault, "ben", "Frage Nummer " + i + "?", null);
            }
            assertThatThrownBy(() -> ask.ask(vault, "ben", "Noch eine Frage?", null)).isInstanceOf(AiWriteRefusedException.class)
                .hasMessageContaining("offene");
            assertThat(ask.ask(vault, "anna", "Noch eine Frage?", null)).isNotNull();
        }

        @Test
        void should_showQuestionsOnlyToTheirAskers() {
            var mine = ask.ask(vault, "ben", "Meine private Frage?", null);
            ask.ask(vault, "anna", "Annas private Frage?", null);

            assertThat(ask.list(vault, "ben")).extracting(view -> view.question().id()).containsExactly(mine.question().id());
            assertThatThrownBy(() -> ask.get(vault, "anna", mine.question().id())).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> ask.delete(vault, "anna", mine.question().id())).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> ask.cancel(vault, "anna", mine.question().id())).isInstanceOf(AiWriteRefusedException.class);
        }

        @Test
        void should_cancelAnOpenQuestion_andDeleteAFinishedOne() {
            var view = ask.ask(vault, "ben", "Meine private Frage?", null);

            assertThatThrownBy(() -> ask.delete(vault, "ben", view.question().id())).isInstanceOf(AiWriteRefusedException.class);
            assertThat(ask.cancel(vault, "ben", view.question().id()).status()).isEqualTo("CANCELLED");
            ask.delete(vault, "ben", view.question().id());
            assertThat(ask.list(vault, "ben")).isEmpty();
        }
    }

    @Nested
    class Stellen {

        @Test
        void should_handOutThePassagesOfReadableNotes_rememberThemAsSources_andAuditWhoSentWhatWhere() {
            var plan = note("Plan.md", "tom", "# Plan\n\nDer Plan nennt drei Meilensteine.");
            note("Geheim/Plan.md", "tom", "Der geheime Plan nennt viele Meilensteine.");
            grants.put(vault, GrantTarget.folder("Geheim"), GrantScope.user("ben"), Set.of(), "tom");
            var view = ask.ask(vault, "ben", "Welche Meilensteine nennt der Plan?", "gemini");
            var job = claim();

            var result = ask.passages(job.id(), null);

            assertThat(result.passages()).singleElement().satisfies(passage -> {
                assertThat(passage.noteId()).isEqualTo(plan);
                assertThat(passage.text()).isEqualTo("Der Plan nennt drei Meilensteine.");
            });
            assertThat(result.local()).isFalse();
            var stored = questions.find(vault, view.question().id()).orElseThrow();
            assertThat(stored.sources()).singleElement().satisfies(source -> {
                assertThat(source.n()).isEqualTo(1);
                assertThat(source.noteId()).isEqualTo(plan);
                assertThat(source.excerpt()).isEqualTo("Der Plan nennt drei Meilensteine.");
            });
            assertThat(audited).containsExactly("ben:ai.ask:null");
            assertThat(auditPayloads.getFirst()).containsEntry("service", "gemini").containsEntry("local", false)
                .containsEntry("notes", List.of(plan.value().toString())).containsEntry("omittedForConsent", 0)
                .containsEntry("questionId", view.question().id().toString())
                .as("no text and not the question itself").doesNotContainValue("Welche Meilensteine nennt der Plan?")
                .doesNotContainValue("Der Plan nennt drei Meilensteine.");
        }

        @Test
        void should_tellHowManyNotesWereLeftOutForLackOfConsent_onlyForExternalServices() {
            note("Von-Tom.md", "tom", "Der Plan nennt drei Meilensteine.");
            note("Von-Anna.md", "anna", "Der Plan nennt vier Meilensteine.");
            var external = ask.ask(vault, "ben", "Welche Meilensteine nennt der Plan?", "gemini");
            claim();
            ask.passages(external.question().jobId(), null);
            var local = ask.ask(vault, "ben", "Welche Meilensteine nennt der Plan?", "lokal");
            claim();
            var localResult = ask.passages(local.question().jobId(), null);

            assertThat(questions.find(vault, external.question().id()).orElseThrow().omittedForConsent()).isEqualTo(1);
            assertThat(ask.get(vault, "ben", external.question().id()).question().omittedForConsent()).isEqualTo(1);
            assertThat(localResult.passages()).hasSize(2);
            assertThat(localResult.local()).isTrue();
            assertThat(questions.find(vault, local.question().id()).orElseThrow().omittedForConsent()).isZero();
        }

        @Test
        void should_refuse_whenThePersonIsNoLongerAMember() {
            note("Plan.md", "tom", "Der Plan nennt drei Meilensteine.");
            var view = ask.ask(vault, "ben", "Welche Meilensteine nennt der Plan?", "lokal");
            claim();
            authorization.removeMember(groups.get("ben"), "ben");

            assertThatThrownBy(() -> ask.passages(view.question().jobId(), null)).isInstanceOf(AiWriteRefusedException.class);
            assertThat(audited).isEmpty();
        }

        @Test
        void should_refuseAQuestionVectorOfTheWrongSize_orWithNonFiniteValues() {
            var view = ask.ask(vault, "ben", "Wo steht das genau?", "lokal");
            claim();
            var nan = new float[384];
            nan[3] = Float.NaN;

            assertThatThrownBy(() -> ask.passages(view.question().jobId(), new float[10])).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> ask.passages(view.question().jobId(), nan)).isInstanceOf(AiWriteRefusedException.class);
            assertThat(audited).isEmpty();
        }

        @Test
        void should_onlyServeRunningQuestionJobs() {
            var view = ask.ask(vault, "ben", "Wo steht das genau?", null);

            assertThatThrownBy(() -> ask.passages(view.question().jobId(), null)).isInstanceOf(de.tstieh.stoneintelligence.platform.ai.AiJobGoneException.class);
            assertThatThrownBy(() -> ask.questionText(UUID.randomUUID())).isInstanceOf(de.tstieh.stoneintelligence.platform.ai.AiJobGoneException.class);
            claim();
            assertThat(ask.questionText(view.question().jobId())).isEqualTo("Wo steht das genau?");
        }
    }

    @Nested
    class Antworten {

        private NoteId plan;
        private UUID questionId;
        private UUID jobId;

        @BeforeEach
        void passages() {
            plan = note("Plan.md", "tom", "# Plan\n\n## Phase eins\n\nDer Plan nennt drei Meilensteine.\n\n## Phase zwei\n\nDer Plan endet im Mai.");
            var view = ask.ask(vault, "ben", "Welche Meilensteine nennt der Plan und wann endet er?", "lokal");
            questionId = view.question().id();
            jobId = claim().id();
            assertThat(ask.passages(jobId, null).passages()).hasSize(2);
        }

        @Test
        void should_keepTheAnswer_withOnlyTheCitedSources_resolvedToTheLivePath() {
            ask.answer(jobId, true, "Es sind drei Meilensteine [1].");
            jobs.complete(jobId);

            var view = ask.get(vault, "ben", questionId);

            assertThat(view.status()).isEqualTo("ANSWERED");
            assertThat(view.restricted()).isFalse();
            assertThat(view.question().answer()).isEqualTo("Es sind drei Meilensteine [1].");
            assertThat(view.citations()).singleElement().satisfies(citation -> {
                assertThat(citation.n()).isEqualTo(1);
                assertThat(citation.noteId()).isEqualTo(plan);
                assertThat(citation.path()).isEqualTo("Plan.md");
                assertThat(citation.heading()).isEqualTo("Phase eins");
                assertThat(citation.excerpt()).isEqualTo("Der Plan nennt drei Meilensteine.");
            });
        }

        @Test
        void should_acceptGroupedCitations() {
            ask.answer(jobId, true, "Drei Meilensteine, Ende im Mai [1, 2].");

            assertThat(ask.get(vault, "ben", questionId).citations()).extracting(AskService.Citation::n).containsExactly(1, 2);
        }

        @Test
        void should_refuseAnAnswerWithoutAValidCitation() {
            assertThatThrownBy(() -> ask.answer(jobId, true, "Es sind drei Meilensteine.")).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> ask.answer(jobId, true, "Es sind drei Meilensteine [7].")).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> ask.answer(jobId, true, "  ")).isInstanceOf(AiWriteRefusedException.class);
            assertThat(questions.find(vault, questionId).orElseThrow().answered()).isNull();
        }

        @Test
        void should_recordThatTheNotesDoNotAnswer_withoutAnyModelText() {
            ask.answer(jobId, false, "Das weiß ich nicht, aber in Wikipedia steht...");
            jobs.complete(jobId);

            var view = ask.get(vault, "ben", questionId);

            assertThat(view.status()).isEqualTo("NOT_ANSWERABLE");
            assertThat(view.question().answer()).isNull();
            assertThat(view.citations()).isEmpty();
        }

        @Test
        void should_hideTheAnswer_whenTheCitedNoteIsNoLongerReadable() {
            ask.answer(jobId, true, "Es sind drei Meilensteine [1].");
            jobs.complete(jobId);

            grants.put(vault, GrantTarget.entry(plan, "Plan.md"), GrantScope.user("ben"), Set.of(), "tom");
            var hidden = ask.get(vault, "ben", questionId);

            assertThat(hidden.restricted()).isTrue();
            assertThat(hidden.question().answer()).isNull();
            assertThat(hidden.citations()).isEmpty();
            assertThat(ask.list(vault, "ben").getFirst().restricted()).isTrue();
        }

        @Test
        void should_hideTheAnswer_whenTheCitedNoteWasDeleted() {
            ask.answer(jobId, true, "Es sind drei Meilensteine [1].");
            notes.delete(vault, plan, UUID.randomUUID().toString(), "tom");

            assertThat(ask.get(vault, "ben", questionId).restricted()).isTrue();
        }

        @Test
        void should_reportAFailedJob() {
            jobs.fail(jobId, "Modell nicht erreichbar", false);

            var view = ask.get(vault, "ben", questionId);

            assertThat(view.status()).isEqualTo("FAILED");
            assertThat(view.job().error()).isEqualTo("Modell nicht erreichbar");
        }
    }
}
