package de.raindancer118.stoneai.extract;

import de.raindancer118.stoneai.chunk.Chunk;
import de.raindancer118.stoneai.chunk.Provenance;
import de.raindancer118.stoneai.config.StoneAiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionServiceTest {

    private static final String VALID = """
            {"concepts": [
              {"title": "Äquivalenzrelation",
               "aliases": ["Äquivalenzrelationen"],
               "definition": "Eine reflexive, symmetrische und transitive Relation.",
               "body": "Eine **Äquivalenzrelation** zerlegt eine Menge in Klassen.",
               "tags": ["mathematik/relationen"],
               "entities": {"begriff": ["Reflexivität", "Symmetrie"]},
               "related": ["Relation"],
               "confidence": 0.9}
            ]}
            """;

    private final StoneAiConfig config = StoneAiConfig.defaults();

    private static Chunk chunk(String text) {
        return new Chunk(0, text, new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 42, null));
    }

    @Nested
    @DisplayName("Reading a well-formed answer")
    class HappyPath {

        @Test
        @DisplayName("should turn the model's JSON into concepts with their provenance")
        void should_produceConcepts_when_answerIsValid() {
            ScriptedLlm llm = new ScriptedLlm(VALID);

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Text")));

            assertThat(result.failures()).isEmpty();
            assertThat(result.concepts()).hasSize(1);
            ExtractedConcept concept = result.concepts().get(0);
            assertThat(concept.title()).isEqualTo("Äquivalenzrelation");
            assertThat(concept.aliases()).containsExactly("Äquivalenzrelationen");
            assertThat(concept.entities()).containsEntry("begriff", List.of("Reflexivität", "Symmetrie"));
            assertThat(concept.related()).containsExactly("Relation");
            assertThat(concept.confidence()).isEqualTo(0.9);
            assertThat(concept.provenance().page()).isEqualTo(42);
        }

        @Test
        @DisplayName("should accept an answer the model wrapped in a markdown code fence")
        void should_stripFence_when_answerIsFenced() {
            ScriptedLlm llm = new ScriptedLlm("Klar, hier:\n```json\n" + VALID + "\n```\n");

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Text")));

            assertThat(result.concepts()).hasSize(1);
            assertThat(result.failures()).isEmpty();
        }

        @Test
        @DisplayName("should fill in the optional fields the model left out")
        void should_useDefaults_when_optionalFieldsAreMissing() {
            ScriptedLlm llm = new ScriptedLlm(
                    "{\"concepts\":[{\"title\":\"Gruppe\",\"body\":\"Eine Menge mit Verknüpfung.\"}]}");

            ExtractedConcept concept = new ExtractionService(config, llm)
                    .extract(List.of(chunk("Text"))).concepts().get(0);

            assertThat(concept.aliases()).isEmpty();
            assertThat(concept.tags()).isEmpty();
            assertThat(concept.entities()).isEmpty();
            assertThat(concept.confidence()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("should ask the model in the configured language")
        void should_promptInConfiguredLanguage_when_languageIsSet() {
            ScriptedLlm llm = new ScriptedLlm(VALID);
            config.llm().language("en");

            new ExtractionService(config, llm).extract(List.of(chunk("Text")));

            assertThat(llm.prompts().get(0)).contains("en");
        }
    }

    @Nested
    @DisplayName("Handling a broken answer")
    class Repair {

        @Test
        @DisplayName("should ask once more with the error when the answer is not valid JSON")
        void should_repair_when_firstAnswerIsMalformed() {
            ScriptedLlm llm = new ScriptedLlm("das ist kein JSON", VALID);

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Text")));

            assertThat(llm.prompts()).hasSize(2);
            assertThat(llm.prompts().get(1)).contains("das ist kein JSON");
            assertThat(result.concepts()).hasSize(1);
            assertThat(result.failures()).isEmpty();
        }

        @Test
        @DisplayName("should repair an answer that parses but breaks the schema")
        void should_repair_when_conceptHasNoTitle() {
            ScriptedLlm llm = new ScriptedLlm("{\"concepts\":[{\"body\":\"ohne Titel\"}]}", VALID);

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Text")));

            assertThat(llm.prompts()).hasSize(2);
            assertThat(result.concepts()).hasSize(1);
        }

        @Test
        @DisplayName("should give up after one repair and report the chunk instead of inventing notes")
        void should_reportFailure_when_repairAlsoFails() {
            ScriptedLlm llm = new ScriptedLlm("kaputt", "immer noch kaputt");

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Text")));

            assertThat(llm.prompts()).hasSize(2);
            assertThat(result.concepts()).isEmpty();
            assertThat(result.failures()).hasSize(1);
            assertThat(result.failures().get(0).chunkIndex()).isZero();
            assertThat(result.failures().get(0).reason()).isNotBlank();
        }

        @Test
        @DisplayName("should keep the concepts of the other chunks when one chunk fails")
        void should_continue_when_oneChunkFails() {
            ScriptedLlm llm = new ScriptedLlm("kaputt", "immer noch kaputt", VALID);

            ExtractionResult result = new ExtractionService(config, llm)
                    .extract(List.of(chunk("Text A"), chunk("Text B")));

            assertThat(result.concepts()).hasSize(1);
            assertThat(result.failures()).hasSize(1);
        }

        @Test
        @DisplayName("should treat an empty concept list as a valid answer, not as a failure")
        void should_acceptEmptyResult_when_chunkHasNothingToExtract() {
            ScriptedLlm llm = new ScriptedLlm("{\"concepts\":[]}");

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Text")));

            assertThat(result.concepts()).isEmpty();
            assertThat(result.failures()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Staying inside the budget")
    class Budget {

        @Test
        @DisplayName("should stop calling the model once the token budget is spent")
        void should_stop_when_budgetIsExhausted() {
            config.llm().maxTokensPerRun(1_000);
            ScriptedLlm llm = new ScriptedLlm(VALID, VALID, VALID).withTokensPerCall(600);

            ExtractionResult result = new ExtractionService(config, llm)
                    .extract(List.of(chunk("A"), chunk("B"), chunk("C")));

            assertThat(llm.prompts()).hasSize(2);
            assertThat(result.budgetExhausted()).isTrue();
            assertThat(result.concepts()).hasSize(2);
        }
    }

    /** Hands out canned answers in order and records what it was asked. */
    @Nested
    @DisplayName("Writing the planned topics")
    class Planned {

        private final TopicPlan plan = new TopicPlan(List.of(
                new TopicPlan.Topic("Verkehrsunfall am 06.06.2026", "ereignis", "Hergang", List.of()),
                new TopicPlan.Topic("Tom Stieh", "person", "Rolle", List.of())), true);

        // The prompt shows each topic with its kind - models tend to copy that into the title.
        @Test
        @DisplayName("should drop a kind the model appended to a title")
        void should_stripTheKind_fromTitles() {
            ScriptedLlm llm = new ScriptedLlm("""
                    {"concepts":[{"title":"Tom Stieh (person)","body":"Fahrer."}]}""");

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Text")), plan);

            assertThat(result.concepts()).extracting(ExtractedConcept::title).containsExactly("Tom Stieh");
        }

        // A two-page letter comes in two chunks, but the planner read all of it: nothing new may appear.
        @Test
        @DisplayName("should fold unplanned notes into the main topic when the planner read the whole document")
        void should_foldUnplannedNotes_acrossChunks_whenThePlanIsComplete() {
            ScriptedLlm llm = new ScriptedLlm(
                    "{\"concepts\":[{\"title\":\"Verkehrsunfall am 06.06.2026\",\"body\":\"Seite eins.\"}]}",
                    "{\"concepts\":[{\"title\":\"Schleudertrauma\",\"body\":\"Seite zwei.\"}]}");

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Eins"), chunk("Zwei")), plan);

            assertThat(result.concepts()).extracting(ExtractedConcept::title)
                    .containsOnly("Verkehrsunfall am 06.06.2026");
        }

        @Test
        @DisplayName("should let a chunk add a topic when the planner only saw excerpts")
        void should_keepANewTopic_whenThePlanIsPartial() {
            TopicPlan partial = new TopicPlan(plan.topics(), false);
            ScriptedLlm llm = new ScriptedLlm("{\"concepts\":[{\"title\":\"Photosynthese\",\"body\":\"Kapitel neun.\"}]}");

            ExtractionResult result = new ExtractionService(config, llm).extract(List.of(chunk("Neun")), partial);

            assertThat(result.concepts()).extracting(ExtractedConcept::title).containsExactly("Photosynthese");
        }

        @Test
        @DisplayName("should not show the topics in a form the model mistakes for the title")
        void should_quoteTheTitles_inThePrompt() {
            ScriptedLlm llm = new ScriptedLlm("{\"concepts\":[]}");

            new ExtractionService(config, llm).extract(List.of(chunk("Text")), plan);

            assertThat(llm.prompts().getFirst()).contains("„Tom Stieh“").doesNotContain("Tom Stieh (person)");
        }
    }

    private static final class ScriptedLlm implements LlmClient {

        private final Deque<String> answers = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();
        private int tokensPerCall;

        ScriptedLlm(String... scripted) {
            answers.addAll(List.of(scripted));
        }

        ScriptedLlm withTokensPerCall(int tokens) {
            this.tokensPerCall = tokens;
            return this;
        }

        List<String> prompts() {
            return prompts;
        }

        @Override
        public LlmAnswer complete(Tier tier, String system, String user) {
            prompts.add(system + "\n" + user);
            String answer = answers.pollFirst();
            if (answer == null) {
                throw new IllegalStateException("ScriptedLlm ran out of answers");
            }
            return new LlmAnswer(answer, tokensPerCall, "fake");
        }

        @Override
        public LlmAnswer readImage(byte[] pngImage, String prompt) {
            return new LlmAnswer("", 0, "fake");
        }
    }
}
