package de.raindancer118.stoneintelligence.worker.ingest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;
import de.raindancer118.stoneintelligence.worker.platform.ClaimedJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobProcessorTest {

    private static final String ANSWER = """
        {"concepts":[{"title":"Photosynthese","aliases":[],"definition":"Licht wird zu Zucker.",
          "body":"Findet in Chloroplasten statt.","tags":["biologie"],"entities":{},"related":["Zelle"],"confidence":0.9}]}
        """;

    private final FakePlatform platform = new FakePlatform();

    private static LlmClient answering(String answer) {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                return new LlmAnswer(answer, 5, "fake/model");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                return new LlmAnswer("", 0, "fake/vision");
            }
        };
    }

    private static LlmClient failing() {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                throw new IllegalStateException("kein Provider konnte antworten: 429");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new IllegalStateException("kein Provider konnte antworten");
            }
        };
    }

    private ClaimedJob job(String fileName, String content) {
        var job = new ClaimedJob(UUID.randomUUID(), "vault-1", "gemini", "tom", fileName, "text/markdown",
            content.length(), 1, UUID.randomUUID(), 1);
        platform.documents.put(job.jobId(), content.getBytes(StandardCharsets.UTF_8));
        return job;
    }

    private JobProcessor processor(LlmClient llm) {
        return new JobProcessor(platform, service -> llm, ServiceModels.from(java.util.Map.of()), () -> LocalDate.of(2026, 9, 23));
    }

    @Test
    void should_turnADocumentIntoNotes_inTheSharedVault_andReportDone() {
        platform.human("Bio/Zelle.md", "# Zelle\n", 1);

        processor(answering(ANSWER)).process(job("Skript.md", "# Photosynthese\n\nLicht und Wasser.\n"));

        assertThat(platform.events).contains("create Notizen/Photosynthese.md", "create Quellen/Skript.md", "complete");
        var note = platform.notes.values().stream().filter(n -> n.path().equals("Notizen/Photosynthese.md")).findFirst().orElseThrow();
        assertThat(note.text()).contains("Licht wird zu Zucker.").contains("[[Zelle]]");
        assertThat(platform.events.getFirst()).startsWith("progress");
    }

    // Ein als privat markiertes Dokument erreicht nie einen Anbieter - und ein erneuter Versuch aendert daran nichts.
    @Test
    void should_failPermanently_whenTheDocumentIsProtected() {
        processor(answering(ANSWER)).process(job("Steuer [noai].md", "# Geheim\n\nInhalt.\n"));

        assertThat(platform.events).last().asString().startsWith("fail ").contains("noai");
        assertThat(platform.notes).isEmpty();
    }

    @Test
    void should_retryLater_whenNoModelCouldAnswer() {
        processor(failing()).process(job("Skript.md", "# Photosynthese\n\nText.\n"));

        assertThat(platform.events).last().asString().startsWith("retry ");
    }

    @Test
    void should_retryLater_whenTheDocumentIsGone() {
        var job = job("Skript.md", "x");
        platform.documents.remove(job.jobId());

        processor(answering(ANSWER)).process(job);

        assertThat(platform.events).last().asString().startsWith("retry ");
    }
}
