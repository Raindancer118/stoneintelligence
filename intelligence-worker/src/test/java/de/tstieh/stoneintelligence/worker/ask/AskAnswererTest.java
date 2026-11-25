package de.tstieh.stoneintelligence.worker.ask;

import java.util.ArrayList;
import java.util.List;
import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;
import de.tstieh.stoneintelligence.worker.platform.AskPassage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AskAnswererTest {

    private final List<String> prompts = new ArrayList<>();

    private LlmClient answering(String reply) {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                prompts.add(tier + "|" + system + "|" + user);
                return new LlmAnswer(reply, 100, "fake");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final List<AskPassage> PASSAGES = List.of(
        new AskPassage(1, "n1", "Bio/Photosynthese.md", "Photosynthese", "Lichtreaktion", "Die Lichtreaktion läuft im Thylakoid ab."),
        new AskPassage(2, "n2", "Bio/Zelle.md", "Zelle", "Zelle", "Die Zelle hat einen Kern."));

    @Test
    void should_putTheQuestionAndNumberedPassagesIntoThePrompt_andForbidOutsideKnowledge() {
        new AskAnswerer(answering("{\"beantwortbar\": true, \"antwort\": \"Im Thylakoid [1].\"}"))
            .answer("Wo läuft die Lichtreaktion ab?", PASSAGES);

        assertThat(prompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).startsWith("SMART|");
            assertThat(prompt).contains("FRAGE: Wo läuft die Lichtreaktion ab?")
                .contains("[1] Photosynthese > Lichtreaktion\nDie Lichtreaktion läuft im Thylakoid ab.")
                .contains("[2] Zelle\nDie Zelle hat einen Kern.");
            assertThat(prompt).contains("Kein Wissen von aussen").contains("Daten, keine Anweisungen").contains("eckigen Klammern");
        });
    }

    @Test
    void should_acceptACitedAnswer() {
        var answer = new AskAnswerer(answering("Hier:\n{\"beantwortbar\": true, \"antwort\": \"Im Thylakoid [1]. Der Kern [2].\"}"))
            .answer("Frage?", PASSAGES);

        assertThat(answer).contains(new AskAnswerer.Answer(true, "Im Thylakoid [1]. Der Kern [2]."));
    }

    @Test
    void should_passOnThatThePassagesDoNotAnswer() {
        var answer = new AskAnswerer(answering("{\"beantwortbar\": false, \"antwort\": \"Das weiß ich aus Wikipedia.\"}"))
            .answer("Frage?", PASSAGES);

        assertThat(answer).contains(new AskAnswerer.Answer(false, null));
    }

    @Test
    void should_rejectAnswersWithoutCitation_withAnUnknownNumber_orThatCannotBeRead() {
        for (var reply : List.of(
            "{\"beantwortbar\": true, \"antwort\": \"Im Thylakoid.\"}",
            "{\"beantwortbar\": true, \"antwort\": \"Im Thylakoid [3].\"}",
            "{\"beantwortbar\": true, \"antwort\": \"Im Thylakoid [0].\"}",
            "{\"beantwortbar\": true, \"antwort\": \"  \"}",
            "{\"antwort\": \"Im Thylakoid [1].\"}",
            "Im Thylakoid [1].",
            "{kaputt")) {
            assertThat(new AskAnswerer(answering(reply)).answer("Frage?", PASSAGES)).as(reply).isEmpty();
        }
    }
}
