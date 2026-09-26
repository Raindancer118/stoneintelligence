package de.tstieh.stoneintelligence.stoneai.extract;

import de.tstieh.stoneintelligence.stoneai.chunk.Chunk;
import de.tstieh.stoneintelligence.stoneai.chunk.Provenance;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.pipeline.ProgressSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final StoneAiConfig config = sequential();

    /** The scripted fake answers in order - one call at a time keeps that order meaningful. */
    private static StoneAiConfig sequential() {
        StoneAiConfig config = StoneAiConfig.defaults();
        config.llm().parallelCalls(1);
        return config;
    }

    private static Chunk chunk(String text) {
        return new Chunk(0, text, new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 42, null));
    }

    private static Chunk chunk(int index, String text) {
        return new Chunk(index, text, new Provenance("Skript", Path.of("/tmp/Skript.pdf"), index + 1, null));
    }

    // Ein 500-Seiten-Buch sind 150 Abschnitte - nacheinander gelesen dauert das Stunden.
    @Nested
    @DisplayName("Reading many sections at once")
    class Parallel {

        /** Answers after a pause that is longest for the first section, so they finish in reverse. */
        private final class SlowLlm implements LlmClient {
            final AtomicInteger running = new AtomicInteger();
            final AtomicInteger peak = new AtomicInteger();
            final AtomicInteger calls = new AtomicInteger();
            final int tokens;

            SlowLlm(int tokens) {
                this.tokens = tokens;
            }

            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                calls.incrementAndGet();
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                String section = user.replaceAll("(?s).*Abschnitt-(\\d+).*", "$1");
                try {
                    Thread.sleep(200 - Integer.parseInt(section) * 15L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
                return new LlmAnswer("{\"concepts\":[{\"title\":\"Thema " + section + "\",\"body\":\"Text " + section + ".\"}]}",
                        tokens, "fake");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                return new LlmAnswer("", 0, "fake");
            }
        }

        private List<Chunk> sections(int count) {
            List<Chunk> chunks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                chunks.add(chunk(i, "Abschnitt-" + i));
            }
            return chunks;
        }

        @Test
        @DisplayName("should ask for several sections at once, up to llm.parallelCalls")
        void should_readSectionsConcurrently() {
            StoneAiConfig parallel = StoneAiConfig.defaults();
            parallel.llm().parallelCalls(4);
            SlowLlm llm = new SlowLlm(1);

            ExtractionResult result = new ExtractionService(parallel, llm).extract(sections(8));

            assertThat(llm.peak.get()).isEqualTo(4);
            assertThat(result.concepts()).hasSize(8);
        }

        @Test
        @DisplayName("should keep the document's order although later sections finish first")
        void should_keepTheDocumentOrder() {
            StoneAiConfig parallel = StoneAiConfig.defaults();
            parallel.llm().parallelCalls(4);

            ExtractionResult result = new ExtractionService(parallel, new SlowLlm(1)).extract(sections(6));

            assertThat(result.concepts()).extracting(ExtractedConcept::title)
                    .containsExactly("Thema 0", "Thema 1", "Thema 2", "Thema 3", "Thema 4", "Thema 5");
        }

        @Test
        @DisplayName("should start no further section once the budget is spent and name the ones left out")
        void should_stopStarting_whenTheBudgetIsSpent() {
            StoneAiConfig parallel = StoneAiConfig.defaults();
            parallel.llm().parallelCalls(2);
            SlowLlm llm = new SlowLlm(1_000);

            ExtractionResult result = new ExtractionService(parallel, llm).extract(sections(6), TopicPlan.none(), 1_000);

            // Two start together (nothing spent yet); the first answer spends the budget.
            assertThat(llm.calls.get()).isEqualTo(2);
            assertThat(result.budgetExhausted()).isTrue();
            assertThat(result.unprocessed()).containsExactly("Skript, S. 3", "Skript, S. 4", "Skript, S. 5", "Skript, S. 6");
        }

        @Test
        @DisplayName("should pass on an exhausted quota instead of reporting the sections as failed")
        void should_propagate_whenOutOfCapacity() {
            StoneAiConfig parallel = StoneAiConfig.defaults();
            LlmClient llm = new LlmClient() {
                @Override
                public LlmAnswer complete(Tier tier, String system, String user) {
                    throw new LlmCapacityException("kein Kontingent frei", null, null);
                }

                @Override
                public LlmAnswer readImage(byte[] pngImage, String prompt) {
                    return new LlmAnswer("", 0, "fake");
                }
            };

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> new ExtractionService(parallel, llm).extract(sections(5)))
                    .isInstanceOf(LlmCapacityException.class);
        }
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

    // Issue #1: Tabellen des Dokuments blieben nicht als Tabellen erhalten.
    @Nested
    @DisplayName("Tables")
    class Tables {

        @Test
        @DisplayName("should ask for the document's tables as Markdown tables")
        void should_askForMarkdownTables() {
            assertThat(Prompts.extractionSystem("de", false)).contains("Markdown-Tabelle");
            assertThat(Prompts.ocr(1, "de")).contains("Markdown-Tabelle");
            assertThat(Prompts.extractionSystem("de", false)).contains("Erfinde keine Zellen");
        }
    }

    // Der Job muss einer 550-Seiten-Vorlage einen echten Zwischenstand zeigen koennen, nicht nur
    // "gestartet" und "fertig".
    @Nested
    @DisplayName("Progress")
    class Progress {

        @Test
        @DisplayName("should report progress per chunk, ending at 100")
        void should_reportProgress_perChunk() {
            ScriptedLlm llm = new ScriptedLlm("{\"concepts\":[]}", "{\"concepts\":[]}", "{\"concepts\":[]}");
            Map<Integer, String> reported = new TreeMap<>();
            ProgressSink sink = (message, percent) -> reported.put(percent, message);

            new ExtractionService(config, llm, sink).extract(List.of(chunk("A"), chunk("B"), chunk("C")));

            assertThat(reported.keySet()).contains(0, 33, 66, 100);
            assertThat(reported.get(100)).isNotBlank();
        }
    }

    // Die KI darf nur verwenden, was im Dokument und im Vault steht - nie Wissen von aussen.
    @Nested
    @DisplayName("Sources only")
    class SourcesOnly {

        @Test
        @DisplayName("should forbid knowledge from outside the document when planning and writing")
        void should_restrictToTheDocument() {
            assertThat(Prompts.extractionSystem("de", true)).contains("ausschließlich Informationen aus dem Text")
                    .contains("Kein Wissen von außen");
            assertThat(Prompts.planSystem("de")).contains("ausschließlich aus dem Dokument und den schon vorhandenen");
            assertThat(Prompts.ocr(1, "de")).contains("ergänze nichts");
        }
    }
}
