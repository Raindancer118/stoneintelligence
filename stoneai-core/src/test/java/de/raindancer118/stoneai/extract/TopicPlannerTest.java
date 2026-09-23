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
