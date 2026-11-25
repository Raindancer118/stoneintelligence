package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.ai.AiJobService;
import de.tstieh.stoneintelligence.platform.ai.AiService;
import de.tstieh.stoneintelligence.platform.ai.AiServiceDirectory;
import de.tstieh.stoneintelligence.platform.ai.FakeAiJobRepository;
import de.tstieh.stoneintelligence.platform.ai.FakeNoteEmbeddingRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.search.FakeNoteTextIndex;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class AskControllerTest {

    private final VaultId vault = VaultId.newId();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeNoteTextIndex texts = new FakeNoteTextIndex();
    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final VaultAccessGuard access = new VaultAccessGuard(authorization, new FakeAccessGrantRepository(notes));
    private final FakeAiJobRepository jobRepository = new FakeAiJobRepository();
    private final AiServiceDirectory services = new AiServiceDirectory(List.of(new AiService("lokal", "Ollama lokal", Set.of(1), true)));
    private final AiJobService jobs = new AiJobService(jobRepository, () -> services, (v, s, by, label) -> UUID.randomUUID(),
        (v, set, actor) -> { }, Instant::now);
    private final AskService service = new AskService(new FakeQuestionRepository(), jobs, jobRepository, services,
        new AskRetrieval(texts, new FakeNoteEmbeddingRepository(notes), notes, access, AiService::mayProcess, 0.8), access, notes,
        v -> Set.of(), (v, note, actor, action, payload) -> { }, Instant::now);
    private final AskController controller = new AskController(service);
    private final AskInternalController internal = new AskInternalController(service);
    private final TestingAuthenticationToken ben = new TestingAuthenticationToken("ben", null);

    private void member() {
        var role = authorization.createRole(vault, "lesen", Set.of(Permission.READ));
        var group = authorization.createGroup(vault, "alle");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "ben");
    }

    @Test
    void should_walkThroughAQuestion_fromAskingToACitedAnswer() {
        member();
        var note = notes.create(vault, "Wissen/Plan.md", NoteLevel.of(1), "tom");
        texts.upsert(note.id(), vault, 1, "# Plan\n\nDer Plan nennt drei Meilensteine.");

        var asked = controller.ask(vault.value().toString(), new AskController.AskRequest("Welche Meilensteine nennt der Plan?", null), ben);
        assertThat(asked.status()).isEqualTo("PENDING");
        assertThat(asked.answer()).isNull();

        var job = jobs.claim().orElseThrow();
        assertThat(internal.question(job.id()).question()).isEqualTo("Welche Meilensteine nennt der Plan?");
        var passages = internal.passages(job.id(), new AskInternalController.PassagesRequest(null));
        assertThat(passages.local()).isTrue();
        assertThat(passages.passages()).singleElement().satisfies(passage -> {
            assertThat(passage.n()).isEqualTo(1);
            assertThat(passage.path()).isEqualTo("Wissen/Plan.md");
        });
        internal.answer(job.id(), new AskInternalController.AnswerRequest(true, "Drei Meilensteine [1]."));
        jobs.complete(job.id());

        var done = controller.get(vault.value().toString(), asked.id(), ben);
        assertThat(done.status()).isEqualTo("ANSWERED");
        assertThat(done.answer()).isEqualTo("Drei Meilensteine [1].");
        assertThat(done.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.noteId()).isEqualTo(note.id().value().toString());
            assertThat(citation.path()).isEqualTo("Wissen/Plan.md");
        });
        assertThat(controller.history(vault.value().toString(), ben)).hasSize(1);

        controller.delete(vault.value().toString(), asked.id(), ben);
        assertThat(controller.history(vault.value().toString(), ben)).isEmpty();
    }

    @Test
    void should_showTheErrorOnlyOfAFailedQuestion() {
        member();
        var asked = controller.ask(vault.value().toString(), new AskController.AskRequest("Welche Meilensteine nennt der Plan?", null), ben);
        var job = jobs.claim().orElseThrow();
        assertThat(controller.get(vault.value().toString(), asked.id(), ben).error()).isNull();

        jobs.fail(job.id(), "Modell nicht erreichbar", false);

        var failed = controller.get(vault.value().toString(), asked.id(), ben);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).isEqualTo("Modell nicht erreichbar");
    }
}
