package de.tstieh.stoneintelligence.worker.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.LlmCapacityException;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;
import de.tstieh.stoneintelligence.worker.platform.AskPassage;
import de.tstieh.stoneintelligence.worker.platform.AskPassages;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AskRunTest {

    private final FakePlatform platform = new FakePlatform();
    private final WordEmbedder embedder = new WordEmbedder();
    private final List<String> prompts = new ArrayList<>();

    private static ClaimedJob askJob() {
        return new ClaimedJob(UUID.randomUUID(), "vault-1", "gemini", "tom", "Frage an den Vault", "text/plain", 0, 1, null, 1, "ASK");
    }

    private LlmClient answering(String reply) {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                prompts.add(user);
                return new LlmAnswer(reply, 100, "fake");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private void found(boolean local, int omitted) {
        platform.askPassages = new AskPassages(List.of(
            new AskPassage(1, "n1", "Bio/Photosynthese.md", "Photosynthese", "Lichtreaktion", "Die Lichtreaktion läuft im Thylakoid ab.")),
            local, omitted);
    }

    @Test
    void should_embedTheQuestion_askTheServerForPassages_answerWithCitations_andFinish() {
        found(false, 0);

        new AskRun(platform, embedder, () -> answering("{\"beantwortbar\": true, \"antwort\": \"Im Thylakoid [1].\"}")).run(askJob());

        assertThat(platform.questionVectors).singleElement().satisfies(vector -> assertThat(vector).hasSize(WordEmbedder.SIZE));
        assertThat(prompts).singleElement().asString().contains("FRAGE: Wo läuft die Lichtreaktion ab?").contains("Thylakoid");
        assertThat(platform.answers).containsExactly("true|Im Thylakoid [1].");
        assertThat(platform.events).last().isEqualTo("complete");
    }

    @Test
    void should_notAskTheModelAtAll_whenThereAreNoPassages_butSayTheNotesDoNotAnswer() {
        new AskRun(platform, embedder, () -> answering("sollte nie gefragt werden")).run(askJob());

        assertThat(prompts).isEmpty();
        assertThat(platform.answers).containsExactly("false|null");
        assertThat(platform.events).last().isEqualTo("complete");
    }

    @Test
    void should_fallBackToFullText_whenThereIsNoEmbeddingModelOrItFails() {
        found(true, 0);

        new AskRun(platform, null, () -> answering("{\"beantwortbar\": true, \"antwort\": \"Im Thylakoid [1].\"}")).run(askJob());
        var failing = new WordEmbedder() {
            @Override
            public float[][] embed(List<String> texts, Kind kind) {
                throw new IllegalStateException("Modell kaputt");
            }
        };
        new AskRun(platform, failing, () -> answering("{\"beantwortbar\": true, \"antwort\": \"Im Thylakoid [1].\"}")).run(askJob());

        assertThat(platform.questionVectors).hasSize(2).containsOnlyNulls();
        assertThat(platform.answers).hasSize(2);
    }

    @Test
    void should_recordThatNothingIsAnswerable_whenTheModelSaysSo() {
        found(false, 2);

        new AskRun(platform, embedder, () -> answering("{\"beantwortbar\": false, \"antwort\": \"\"}")).run(askJob());

        assertThat(platform.answers).containsExactly("false|null");
        assertThat(platform.events).last().isEqualTo("complete");
    }

    @Test
    void should_failAndRetry_whenTheAnswerIsNotUsable_neverStoringIt() {
        found(false, 0);

        new AskRun(platform, embedder, () -> answering("Im Thylakoid, weiß ich aus dem Studium.")).run(askJob());

        assertThat(platform.answers).isEmpty();
        assertThat(platform.events).last().asString().startsWith("retry ");
    }

    @Test
    void should_failForGood_whenNoModelIsConfigured() {
        found(false, 0);

        new AskRun(platform, embedder, () -> null).run(askJob());

        assertThat(platform.events).last().asString().startsWith("fail ").contains("Sprachmodell");
    }

    @Test
    void should_letCapacityProblemsBubbleUp_soTheJobWaits() {
        found(false, 0);
        var empty = new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                throw new LlmCapacityException("kein Kontingent frei: groq 429", java.time.Instant.parse("2026-09-26T07:00:00Z"));
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> new AskRun(platform, embedder, () -> empty).run(askJob())).isInstanceOf(LlmCapacityException.class);
        assertThat(platform.answers).isEmpty();
    }
}
