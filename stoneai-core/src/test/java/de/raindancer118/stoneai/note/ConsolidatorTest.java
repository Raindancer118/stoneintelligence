package de.raindancer118.stoneai.note;

import de.raindancer118.stoneai.chunk.Provenance;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.ExtractedConcept;
import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsolidatorTest {

    private final StoneAiConfig config = StoneAiConfig.defaults();

    private static ExtractedConcept concept(String title, String body, int page) {
        return concept(title, body, page, List.of(), 0.8);
    }

    private static ExtractedConcept concept(String title, String body, int page,
                                            List<String> aliases, double confidence) {
        return new ExtractedConcept(title, aliases, "Definition von " + title, body,
                List.of("thema"), Map.of("begriff", List.of(title)), List.of(), confidence,
                new Provenance("Skript", Path.of("/tmp/Skript.pdf"), page, null));
    }

    @Nested
    @DisplayName("Merging duplicates")
    class Merging {

        @Test
        @DisplayName("should merge concepts with the same title into one note citing both pages")
        void should_merge_when_titlesAreIdentical() {
            ScriptedLlm llm = new ScriptedLlm("Zusammengeführter Text über Gruppen.");

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(List.of(
                    concept("Gruppe", "Erste Erklärung.", 3),
                    concept("Gruppe", "Zweite Erklärung.", 9)));

            assertThat(notes).hasSize(1);
            assertThat(notes.get(0).title()).isEqualTo("Gruppe");
            assertThat(notes.get(0).body()).isEqualTo("Zusammengeführter Text über Gruppen.");
            assertThat(notes.get(0).sources()).extracting(Provenance::page).containsExactly(3, 9);
            assertThat(llm.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("should merge titles that differ only slightly")
        void should_merge_when_titlesAreNearlyIdentical() {
            ScriptedLlm llm = new ScriptedLlm("Zusammengeführt.");

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(List.of(
                    concept("Äquivalenzrelation", "A", 1),
                    concept("Äquivalenzrelationen", "B", 2)));

            assertThat(notes).hasSize(1);
        }

        @Test
        @DisplayName("should keep clearly different concepts apart")
        void should_notMerge_when_titlesAreDifferent() {
            ScriptedLlm llm = new ScriptedLlm();

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(List.of(
                    concept("Gruppe", "A", 1),
                    concept("Ring", "B", 2)));

            assertThat(notes).hasSize(2);
            assertThat(llm.calls()).isZero();
        }

        @Test
        @DisplayName("should merge when one concept's alias is the other's title")
        void should_merge_when_aliasMatchesOtherTitle() {
            ScriptedLlm llm = new ScriptedLlm("Zusammengeführt.");

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(List.of(
                    concept("Halbgruppe", "A", 1, List.of("Monoid"), 0.8),
                    concept("Monoid", "B", 2)));

            assertThat(notes).hasSize(1);
            assertThat(notes.get(0).aliases()).contains("Monoid");
        }

        @Test
        @DisplayName("should take the union of aliases, tags and entities when merging")
        void should_unionMetadata_when_merging() {
            ScriptedLlm llm = new ScriptedLlm("Zusammengeführt.");
            ExtractedConcept first = new ExtractedConcept("Gruppe", List.of("Gruppen"), "d", "A",
                    List.of("algebra"), Map.of("begriff", List.of("Assoziativität")), List.of("Ring"),
                    0.8, new Provenance("S", Path.of("/tmp/s.pdf"), 1, null));
            ExtractedConcept second = new ExtractedConcept("Gruppe", List.of("Grp"), "d", "B",
                    List.of("mathe"), Map.of("begriff", List.of("Neutrales Element")), List.of("Körper"),
                    0.9, new Provenance("S", Path.of("/tmp/s.pdf"), 2, null));

            DraftNote note = new Consolidator(config, llm).consolidate(List.of(first, second)).get(0);

            assertThat(note.aliases()).contains("Gruppen", "Grp");
            assertThat(note.tags()).contains("algebra", "mathe");
            assertThat(note.entities().get("begriff")).contains("Assoziativität", "Neutrales Element");
            assertThat(note.related()).contains("Ring", "Körper");
            assertThat(note.confidence()).isEqualTo(0.9);
        }
    }

    @Nested
    @DisplayName("Passing single concepts through")
    class SingleConcepts {

        @Test
        @DisplayName("should not spend a model call on a concept that has no duplicate")
        void should_skipModel_when_conceptIsUnique() {
            ScriptedLlm llm = new ScriptedLlm();

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(
                    List.of(concept("Gruppe", "Der einzige Text.", 1)));

            assertThat(llm.calls()).isZero();
            assertThat(notes.get(0).body()).isEqualTo("Der einzige Text.");
        }
    }

    @Nested
    @DisplayName("When the merge call fails")
    class Failures {

        @Test
        @DisplayName("should keep both texts rather than lose content")
        void should_concatenate_when_mergeCallFails() {
            ScriptedLlm llm = new ScriptedLlm().failing();

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(List.of(
                    concept("Gruppe", "Erster Text.", 1),
                    concept("Gruppe", "Zweiter Text.", 2)));

            assertThat(notes).hasSize(1);
            assertThat(notes.get(0).body()).contains("Erster Text.").contains("Zweiter Text.");
        }

        @Test
        @DisplayName("should fall back rather than accept an empty merge result")
        void should_concatenate_when_mergeReturnsNothing() {
            ScriptedLlm llm = new ScriptedLlm("   ");

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(List.of(
                    concept("Gruppe", "Erster Text.", 1),
                    concept("Gruppe", "Zweiter Text.", 2)));

            assertThat(notes.get(0).body()).contains("Erster Text.").contains("Zweiter Text.");
        }
    }

    @Nested
    @DisplayName("Limits")
    class Limits {

        @Test
        @DisplayName("should cap the number of notes and keep the most confident ones")
        void should_keepMostConfident_when_overTheLimit() {
            config.notes().maxNotesPerDocument(2);
            ScriptedLlm llm = new ScriptedLlm();

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(List.of(
                    concept("Alpha", "A", 1, List.of(), 0.3),
                    concept("Beta", "B", 2, List.of(), 0.9),
                    concept("Gamma", "C", 3, List.of(), 0.7)));

            assertThat(notes).hasSize(2);
            assertThat(notes).extracting(DraftNote::title).containsExactlyInAnyOrder("Beta", "Gamma");
        }

        @Test
        @DisplayName("should produce nothing when there was nothing to consolidate")
        void should_returnEmpty_when_noConcepts() {
            assertThat(new Consolidator(config, new ScriptedLlm()).consolidate(List.of())).isEmpty();
        }
    }

    private static final class ScriptedLlm implements LlmClient {

        private final Deque<String> answers = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();
        private boolean fail;

        ScriptedLlm(String... scripted) {
            answers.addAll(List.of(scripted));
        }

        ScriptedLlm failing() {
            this.fail = true;
            return this;
        }

        int calls() {
            return prompts.size();
        }

        @Override
        public LlmAnswer complete(Tier tier, String system, String user) {
            prompts.add(user);
            if (fail) {
                throw new IllegalStateException("provider unavailable");
            }
            String answer = answers.pollFirst();
            if (answer == null) {
                throw new IllegalStateException("ScriptedLlm ran out of answers");
            }
            return new LlmAnswer(answer, 10, "fake");
        }

        @Override
        public LlmAnswer readImage(byte[] pngImage, String prompt) {
            return new LlmAnswer("", 0, "fake");
        }
    }
}
