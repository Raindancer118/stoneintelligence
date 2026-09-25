package de.raindancer118.stoneai.extract;

import de.raindancer118.stoneai.chunk.Chunk;
import de.raindancer118.stoneai.chunk.Chunker;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.source.DocumentKind;
import de.raindancer118.stoneai.source.Page;
import de.raindancer118.stoneai.source.SourceDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicPlannerTest {

    private final StoneAiConfig config = StoneAiConfig.defaults();

    private static SourceDocument deck(int slides) {
        List<Page> pages = new ArrayList<>();
        for (int number = 1; number <= slides; number++) {
            String chapter = number <= slides / 2 ? "Kostenmanagement" : "Jahresabschlussanalyse";
            pages.add(new Page(number, chapter + "\nFolie " + number + " Thema\n" + "Inhalt ".repeat(80), false));
        }
        return new SourceDocument(Path.of("/tmp/Controlling.pdf"), "Controlling I", DocumentKind.PDF, pages, List.of(), false, "hash");
    }

    private static final class Recording implements LlmClient {
        final List<String> prompts = new ArrayList<>();

        @Override
        public LlmAnswer complete(Tier tier, String system, String user) {
            prompts.add(user);
            return new LlmAnswer("{\"topics\":[{\"title\":\"Controlling I\",\"kind\":\"thema\",\"scope\":\"Überblick\"}]}", 1, "fake");
        }

        @Override
        public LlmAnswer readImage(byte[] pngImage, String prompt) {
            return new LlmAnswer("", 0, "fake");
        }
    }

    // A 200-slide deck is far more than the planner reads - but it must know every chapter.
    @Test
    @DisplayName("should show the planner the outline of the whole deck, down to the last slide")
    void should_offerTheOutlineOfTheWholeDocument() {
        SourceDocument deck = deck(200);
        List<Chunk> chunks = new Chunker(10_000, 0).split(deck);
        Recording llm = new Recording();

        TopicPlanner.Result result = new TopicPlanner(config, llm).plan(deck, chunks, List.of());

        assertThat(llm.prompts.getFirst()).contains("Gliederung").contains("S. 200: Jahresabschlussanalyse › Folie 200 Thema");
        assertThat(llm.prompts.getFirst().length()).isLessThan(40_000);
        assertThat(result.plan().complete()).isFalse();
    }

    // Bei 300 Abschnitten brach der Auszug nach etwa 70 ab - der Planer sah vom Buch nur den Anfang.
    @Test
    @DisplayName("should excerpt a very long document evenly from the first to the last section")
    void should_excerptTheWholeOfAVeryLongDocument() {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            chunks.add(new Chunk(i, "Abschnitt " + (i + 1) + " " + "Inhalt ".repeat(1_400),
                    new de.raindancer118.stoneai.chunk.Provenance("Buch", Path.of("/tmp/Buch.pdf"), i * 3 + 1, null, i * 3 + 3)));
        }

        String excerpt = TopicPlanner.excerpt(chunks, 24_000);

        assertThat(excerpt).contains("[Buch, S. 1–3]").contains("[Buch, S. 898–900]");
        List<Integer> firstPages = java.util.regex.Pattern.compile("\\[Buch, S\\. (\\d+)–").matcher(excerpt).results()
                .map(match -> Integer.parseInt(match.group(1))).toList();
        assertThat(firstPages).hasSizeGreaterThan(40);
        // Evenly spread: no gap between two excerpts is much wider than the average.
        for (int i = 1; i < firstPages.size(); i++) {
            assertThat(firstPages.get(i) - firstPages.get(i - 1)).isLessThanOrEqualTo(2 * 900 / firstPages.size() + 3);
        }
        assertThat(excerpt.length()).isLessThanOrEqualTo(24_000 * 12 / 10);
    }

    @Test
    @DisplayName("should read a short document whole, without an outline")
    void should_readShortDocumentsWhole() {
        SourceDocument deck = deck(4);
        Recording llm = new Recording();

        TopicPlanner.Result result = new TopicPlanner(config, llm).plan(deck, new Chunker(10_000, 0).split(deck), List.of());

        assertThat(llm.prompts.getFirst()).doesNotContain("Gliederung");
        assertThat(result.plan().complete()).isTrue();
    }
}
