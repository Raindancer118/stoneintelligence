package de.raindancer118.stoneai.chunk;

import de.raindancer118.stoneai.source.DocumentKind;
import de.raindancer118.stoneai.source.Page;
import de.raindancer118.stoneai.source.SourceDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private static SourceDocument markdown(String body) {
        return new SourceDocument(Path.of("/tmp/Notiz.md"), "Notiz", DocumentKind.MARKDOWN,
                List.of(new Page(1, body, false)), List.of(), false, "hash");
    }

    private static SourceDocument pdf(String... pageTexts) {
        List<Page> pages = new java.util.ArrayList<>();
        for (int i = 0; i < pageTexts.length; i++) {
            pages.add(new Page(i + 1, pageTexts[i], false));
        }
        return new SourceDocument(Path.of("/tmp/Skript.pdf"), "Skript", DocumentKind.PDF,
                pages, List.of(), false, "hash");
    }

    @Nested
    @DisplayName("Markdown documents")
    class MarkdownDocuments {

        @Test
        @DisplayName("should cut at headings so a chunk is one topic")
        void should_splitAtHeadings_when_documentHasSections() {
            List<Chunk> chunks = new Chunker(2000, 100).split(markdown("""
                    # Relationen

                    Eine Relation ist eine Teilmenge des Kreuzprodukts.

                    ## Äquivalenzrelation

                    Reflexiv, symmetrisch und transitiv.

                    ## Ordnungsrelation

                    Reflexiv, antisymmetrisch und transitiv.
                    """));

            assertThat(chunks).hasSize(3);
            assertThat(chunks.get(0).text()).contains("Kreuzprodukts");
            assertThat(chunks.get(1).provenance().headingPath()).isEqualTo("Relationen > Äquivalenzrelation");
            assertThat(chunks.get(2).provenance().headingPath()).isEqualTo("Relationen > Ordnungsrelation");
        }

        @Test
        @DisplayName("should number chunks consecutively from zero")
        void should_numberChunks_when_splitting() {
            List<Chunk> chunks = new Chunker(2000, 100).split(
                    markdown("# A\n\nErster Abschnitt.\n\n# B\n\nZweiter Abschnitt.\n"));

            assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1);
        }

        @Test
        @DisplayName("should split a section that exceeds the budget and overlap the pieces")
        void should_splitAndOverlap_when_sectionIsTooLong() {
            String paragraph = "Satz über Mengen. ".repeat(40);
            List<Chunk> chunks = new Chunker(300, 60).split(markdown("# Lang\n\n" + paragraph));

            assertThat(chunks.size()).isGreaterThan(1);
            assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(400));
            String tailOfFirst = chunks.get(0).text();
            assertThat(chunks.get(1).text().substring(0, 20))
                    .isSubstringOf(tailOfFirst.substring(Math.max(0, tailOfFirst.length() - 120)));
            assertThat(chunks).allSatisfy(chunk ->
                    assertThat(chunk.provenance().headingPath()).isEqualTo("Lang"));
        }

        @Test
        @DisplayName("should keep a fenced code block in one piece")
        void should_notSplitInsideCodeFence_when_budgetIsTight() {
            String code = "```java\n" + "int x = 1;\n".repeat(30) + "```";
            List<Chunk> chunks = new Chunker(200, 20).split(markdown("# Code\n\n" + code + "\n"));

            long fenced = chunks.stream().filter(chunk -> chunk.text().contains("```")).count();
            assertThat(fenced).isEqualTo(1);
            assertThat(chunks.stream().filter(chunk -> chunk.text().contains("```")).findFirst().orElseThrow().text())
                    .contains("```java")
                    .endsWith("```");
        }
    }

    @Nested
    @DisplayName("Paged documents")
    class PagedDocuments {

        @Test
        @DisplayName("should record the page a chunk came from")
        void should_carryPageNumber_when_documentIsPaged() {
            List<Chunk> chunks = new Chunker(20, 0).split(pdf("Seite eins Inhalt", "Seite zwei Inhalt"));

            assertThat(chunks).hasSize(2);
            assertThat(chunks.get(0).provenance().page()).isEqualTo(1);
            assertThat(chunks.get(1).provenance().page()).isEqualTo(2);
        }

        // A slide holds a few lines: 200 slides as 200 calls ran out of budget and gave the model
        // no context. The topic plan keeps a chunk's topics apart, not the chunk boundary.
        @Test
        @DisplayName("should pack short pages into one chunk and cite the page range")
        void should_packShortPages_andCiteTheRange() {
            List<Chunk> chunks = new Chunker(100_000, 0).split(pdf("Erste", "Zweite", "Dritte"));

            assertThat(chunks).singleElement().satisfies(chunk -> {
                assertThat(chunk.text()).contains("Erste").contains("Zweite").contains("Dritte");
                assertThat(chunk.provenance().page()).isEqualTo(1);
                assertThat(chunk.provenance().lastPage()).isEqualTo(3);
                assertThat(chunk.provenance().label()).isEqualTo("Skript, S. 1–3");
            });
        }

        @Test
        @DisplayName("should start a new chunk at a page boundary once the budget is reached")
        void should_cutAtPageBoundaries_whenPagesExceedTheBudget() {
            String page = "x".repeat(40);

            List<Chunk> chunks = new Chunker(100, 0).split(pdf(page, page, page, page));

            assertThat(chunks).extracting(chunk -> chunk.provenance().label())
                    .containsExactly("Skript, S. 1–2", "Skript, S. 3–4");
        }

        @Test
        @DisplayName("should mark in the text where each page begins, so a note can cite it")
        void should_markPageStarts() {
            List<Chunk> chunks = new Chunker(100_000, 0).split(pdf("Erste", "Zweite"));

            assertThat(chunks.getFirst().text()).contains("[S. 1]").contains("[S. 2]");
        }

        @Test
        @DisplayName("should carry the document title into every chunk's provenance")
        void should_carryDocumentTitle_when_splitting() {
            List<Chunk> chunks = new Chunker(2000, 100).split(pdf("Inhalt"));

            assertThat(chunks.get(0).provenance().documentTitle()).isEqualTo("Skript");
            assertThat(chunks.get(0).provenance().file().getFileName().toString()).isEqualTo("Skript.pdf");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should produce nothing for a document with only whitespace")
        void should_returnEmpty_when_textIsBlank() {
            assertThat(new Chunker(2000, 100).split(pdf("   \n\n  "))).isEmpty();
        }

        @Test
        @DisplayName("should drop chunks that hold no words worth extracting from")
        void should_dropTinyChunks_when_sectionIsJustAHeading() {
            List<Chunk> chunks = new Chunker(2000, 100).split(markdown("# Nur Überschrift\n\n# Zweite\n\nEcht Inhalt hier drin.\n"));

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).text()).contains("Echt Inhalt");
        }
    }
}
