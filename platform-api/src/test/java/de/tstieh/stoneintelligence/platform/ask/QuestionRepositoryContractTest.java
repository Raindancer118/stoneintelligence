package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vertrag fuer {@code FakeQuestionRepository} und {@code JdbcQuestionRepository}. */
public abstract class QuestionRepositoryContractTest {

    private static final Instant T0 = Instant.parse("2026-11-25T08:00:00Z");

    protected abstract QuestionRepository repository();

    protected abstract VaultId existingVault();

    private Question ask(QuestionRepository repository, VaultId vault, String who, String text, Instant at) {
        return repository.create(Question.asked(vault, who, "gemini", text, UUID.randomUUID(), at));
    }

    @Test
    void should_storeAQuestion_andFindItByIdAndByJob_onlyInItsVault() {
        var repository = repository();
        var vault = existingVault();
        var question = ask(repository, vault, "tom", "Was ist Photosynthese?", T0);

        assertThat(repository.find(vault, question.id())).contains(question);
        assertThat(repository.findByJob(question.jobId())).contains(question);
        assertThat(repository.find(existingVault(), question.id())).isEmpty();
        assertThat(question.answered()).isNull();
    }

    @Test
    void should_keepTheSentPassages_andTheAnswerWithItsCitations() {
        var repository = repository();
        var vault = existingVault();
        var question = ask(repository, vault, "tom", "Wo läuft die Lichtreaktion ab?", T0);
        var note = NoteId.newId();

        repository.saveSources(question.id(), List.of(new Question.Source(1, note, "Lichtreaktion", "Im Thylakoid."),
            new Question.Source(2, note, "Calvin-Zyklus", "Im Stroma.")), 3);
        repository.saveAnswer(question.id(), true, "Im Thylakoid [1].", List.of(1), T0.plusSeconds(30));

        var stored = repository.find(vault, question.id()).orElseThrow();
        assertThat(stored.sources()).extracting(Question.Source::heading).containsExactly("Lichtreaktion", "Calvin-Zyklus");
        assertThat(stored.sources().getFirst().noteId()).isEqualTo(note);
        assertThat(stored.omittedForConsent()).isEqualTo(3);
        assertThat(stored.answered()).isTrue();
        assertThat(stored.answer()).isEqualTo("Im Thylakoid [1].");
        assertThat(stored.cited()).containsExactly(1);
        assertThat(stored.answeredAt()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void should_listOnlyTheOwnQuestions_newestFirst() {
        var repository = repository();
        var vault = existingVault();
        var first = ask(repository, vault, "tom", "Erste", T0);
        var second = ask(repository, vault, "tom", "Zweite", T0.plusSeconds(60));
        ask(repository, vault, "ben", "Fremde", T0.plusSeconds(30));
        ask(repository, existingVault(), "tom", "Anderer Vault", T0.plusSeconds(90));

        assertThat(repository.listBy(vault, "tom", 10)).extracting(Question::id).containsExactly(second.id(), first.id());
        assertThat(repository.listBy(vault, "tom", 1)).hasSize(1);
    }

    @Test
    void should_deleteOnlyTheOwnQuestion() {
        var repository = repository();
        var vault = existingVault();
        var question = ask(repository, vault, "tom", "Frage", T0);

        assertThat(repository.delete(vault, "ben", question.id())).isFalse();
        assertThat(repository.find(vault, question.id())).isPresent();
        assertThat(repository.delete(vault, "tom", question.id())).isTrue();
        assertThat(repository.find(vault, question.id())).isEmpty();
        assertThat(repository.delete(vault, "tom", question.id())).isFalse();
    }

    @Test
    void should_purgeOldQuestions() {
        var repository = repository();
        var vault = existingVault();
        var old = ask(repository, vault, "tom", "Alt", T0);
        var recent = ask(repository, vault, "tom", "Neu", T0.plusSeconds(3600));

        assertThat(repository.purgeAskedBefore(T0.plusSeconds(60))).isEqualTo(1);

        assertThat(repository.find(vault, old.id())).isEmpty();
        assertThat(repository.find(vault, recent.id())).isPresent();
    }
}
