package de.tstieh.stoneintelligence.stoneai.note;

import de.tstieh.stoneintelligence.stoneai.chunk.Provenance;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.extract.ExtractedConcept;
import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;
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

import static org.assertj.core.api.Assertions.assertThat;

class ConsolidatorTest {

    private final StoneAiConfig config = sequential();

    /** The scripted fake answers in order - one merge at a time keeps that order meaningful. */
    private static StoneAiConfig sequential() {
        StoneAiConfig config = StoneAiConfig.defaults();
        config.llm().parallelCalls(1);
        return config;
    }

    /** Merges slowly - topic "A" slowest - and remembers how many merges ran at the same time. */
    private static final class SlowMerger implements LlmClient {
        final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public LlmAnswer complete(Tier tier, String system, String user) {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            String topic = user.replaceAll("(?s)Thema: (\\S+).*", "$1");
            try {
                Thread.sleep(topic.equals("A") ? 250 : 100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            // Long enough not to count as cut off.
            return new LlmAnswer("Zusammengeführt " + topic + ". " + "Inhalt. ".repeat(user.length() / 30), 10, "fake");
        }

        @Override
        public LlmAnswer readImage(byte[] pngImage, String prompt) {
            return new LlmAnswer("", 0, "fake");
        }
    }

    private static StoneAiConfig parallel(int calls) {
        StoneAiConfig config = StoneAiConfig.defaults();
        config.llm().parallelCalls(calls);
        return config;
    }

    // Ein langes Skript hat Dutzende Themen, jedes mit vielen Teilen - nacheinander gemergt dauert das.
    @Test
    @DisplayName("should merge several notes at once and keep their order")
    void should_mergeNotesConcurrently_inOrder() {
        SlowMerger slow = new SlowMerger();
        List<ExtractedConcept> concepts = new ArrayList<>();
        for (String topic : List.of("A", "B", "C", "D", "E", "F")) {
            concepts.add(concept(topic, "Teil eins zu " + topic + ".", 1));
            concepts.add(concept(topic, "Teil zwei zu " + topic + ".", 2));
        }

        List<DraftNote> notes = new Consolidator(parallel(3), slow).consolidate(concepts);

        assertThat(slow.peak.get()).isEqualTo(3);
        assertThat(notes).extracting(DraftNote::title).containsExactly("A", "B", "C", "D", "E", "F");
        assertThat(notes.get(0).body()).startsWith("Zusammengeführt A.");
    }

    // Das Hauptthema eines 1.000-Seiten-Buchs sammelt Hunderte Teile - deren Portionen liefen nacheinander.
    @Test
    @DisplayName("should merge the batches of one large topic at once")
    void should_mergeBatchesConcurrently_whenThereIsOnlyOneTopic() {
        SlowMerger slow = new SlowMerger();
        List<ExtractedConcept> concepts = new ArrayList<>();
        for (int part = 0; part < 20; part++) {
            concepts.add(concept("Hauptthema", ("Teil " + part + ". ").repeat(700), part + 1));
        }

        List<DraftNote> notes = new Consolidator(parallel(4), slow).consolidate(concepts);

        assertThat(slow.peak.get()).isEqualTo(4);
        assertThat(notes).singleElement().extracting(DraftNote::title).isEqualTo("Hauptthema");
    }

    @Test
    @DisplayName("should never exceed llm.parallelCalls, however many topics and batches there are")
    void should_stayWithinTheLimit_acrossTopicsAndBatches() {
        SlowMerger slow = new SlowMerger();
        List<ExtractedConcept> concepts = new ArrayList<>();
        for (int part = 0; part < 12; part++) {
            concepts.add(concept("Hauptthema", ("Teil " + part + ". ").repeat(700), part + 1));
            concepts.add(concept("Nebenthema " + part, ("Neben " + part + ". ").repeat(700), part + 1));
            concepts.add(concept("Nebenthema " + part, ("Mehr " + part + ". ").repeat(700), part + 2));
        }

        List<DraftNote> notes = new Consolidator(parallel(3), slow).consolidate(concepts);

        assertThat(slow.peak.get()).isEqualTo(3);
        assertThat(notes).hasSize(13);
        assertThat(notes.get(0).title()).isEqualTo("Hauptthema");
    }

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

        // Issue #1: beim Zusammenfuehren durften Tabellen nicht wieder zu Stichpunkten werden.
        @Test
        @DisplayName("should ask to keep tables as tables when merging")
        void should_keepTables_when_merging() {
            ScriptedLlm llm = new ScriptedLlm("Zusammengeführter Text über Gruppen.");

            new Consolidator(config, llm).consolidate(List.of(
                    concept("Gruppe", "| a | b |\n|---|---|\n| 1 | 2 |", 3),
                    concept("Gruppe", "Zweite Erklärung.", 9)));

            assertThat(llm.prompts).singleElement().asString().contains("Tabellen bleiben Markdown-Tabellen")
                    .contains("Verwende ausschließlich", "kein Wissen von außen");
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

        // Probelauf "Die Verwandlung": 15 Abschnitte gaben Gregor Samsa 40 Tags ("Kaffee", "Tür",
        // "Familie" und "familie") - die Vereinigung aller Abschnitte ist kein Schlagwort mehr.
        @Test
        @DisplayName("should keep only the most frequent tags of a merged note, without case duplicates")
        void should_keepTheMostFrequentTags_whenMergingManyParts() {
            ScriptedLlm llm = new ScriptedLlm("Zusammengeführter Text über Gregor.");
            List<ExtractedConcept> parts = new ArrayList<>();
            for (int part = 0; part < 15; part++) {
                List<String> tags = new ArrayList<>(List.of(part % 2 == 0 ? "Familie" : "familie", "figur/hauptfigur"));
                tags.add("Einzelheit " + part);
                parts.add(new ExtractedConcept("Gregor Samsa", List.of(), "d", "Teil " + part + ".", tags, Map.of(),
                        List.of(), 0.8, new Provenance("S", Path.of("/tmp/s.pdf"), part + 1, null)));
            }

            DraftNote note = new Consolidator(config, llm).consolidate(parts).get(0);

            assertThat(note.tags()).hasSizeLessThanOrEqualTo(Consolidator.MAX_TAGS);
            assertThat(note.tags().subList(0, 2)).containsExactly("Familie", "figur/hauptfigur");
            assertThat(note.tags()).filteredOn(tag -> tag.equalsIgnoreCase("familie")).hasSize(1);
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

    @Nested
    @DisplayName("Merging a topic spread over a long document")
    class LargeTopics {

        // Thirty sections of a lecture about one concept do not fit into one answer - merged in
        // one call, the note came back cut off without anyone noticing.
        @Test
        @DisplayName("should merge in batches that each fit into one answer")
        void should_mergeInBatches() {
            List<ExtractedConcept> parts = new ArrayList<>();
            for (int page = 1; page <= 10; page++) {
                parts.add(concept("Target Costing", "Abschnitt " + page + " " + "x".repeat(3_000), page));
            }
            String batch = "Zusammengefasst " + "y".repeat(3_000);
            ScriptedLlm llm = new ScriptedLlm(batch, batch, batch, batch, "Gesamter Text " + "z".repeat(6_000));

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(parts);

            assertThat(llm.prompts).allSatisfy(prompt -> assertThat(prompt.length()).isLessThan(16_000));
            assertThat(llm.calls()).isGreaterThan(1);
            assertThat(notes.getFirst().body()).startsWith("Gesamter Text");
        }

        @Test
        @DisplayName("should keep the parts when a merge comes back suspiciously short")
        void should_keepTheParts_whenTheMergeLooksCutOff() {
            ScriptedLlm llm = new ScriptedLlm("Kurz.");

            List<DraftNote> notes = new Consolidator(config, llm).consolidate(List.of(
                    concept("Benchmarking", "Erster Teil " + "a".repeat(2_000), 1),
                    concept("Benchmarking", "Zweiter Teil " + "b".repeat(2_000), 2)));

            assertThat(notes.getFirst().body()).contains("Erster Teil").contains("Zweiter Teil");
        }
    }

    @Nested
    @DisplayName("Progress")
    class Progress {

        @Test
        @DisplayName("should report progress per merged note, ending at 100")
        void should_reportProgress_perGroup() {
            Map<Integer, String> reported = new TreeMap<>();
            ProgressSink sink = (message, percent) -> reported.put(percent, message);

            new Consolidator(config, new ScriptedLlm(), sink).consolidate(List.of(
                    concept("Gruppe", "Erste.", 1), concept("Ring", "Zweite.", 2)));

            assertThat(reported.keySet()).contains(50, 100);
            assertThat(reported.get(100)).isNotBlank();
        }
    }

    // Falling back to the unmerged parts is right for one odd answer, wrong for an empty quota:
    // the notes would come out worse with nobody told why. The run stops and waits instead.
    @Test
    @DisplayName("should stop instead of concatenating when the model is out of capacity")
    void should_propagate_when_mergeIsOutOfCapacity() {
        LlmClient exhausted = new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                throw new de.tstieh.stoneintelligence.stoneai.extract.LlmCapacityException("groq: Kontingent aufgebraucht", null);
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new UnsupportedOperationException();
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Consolidator(config, exhausted).consolidate(List.of(
                        concept("Gruppe", "Erste Erklärung.", 3),
                        concept("Gruppe", "Zweite Erklärung.", 9))))
                .isInstanceOf(de.tstieh.stoneintelligence.stoneai.extract.LlmCapacityException.class);
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
