package de.tstieh.stoneintelligence.worker.embed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoteChunkerTest {

    @Test
    void should_embedEachSection_withTheTitleForContext_andWithoutFrontmatter() {
        var text = "---\naliases: [PS]\n---\n# Photosynthese\n\nEinleitung.\n\n## Lichtreaktion\n\nIm Thylakoid.\n\n## Calvin-Zyklus\n\nIm Stroma.\n";

        var chunks = NoteChunker.chunks("Photosynthese", text);

        assertThat(chunks).extracting(NoteChunker.Section::heading)
            .containsExactly("Photosynthese", "Lichtreaktion", "Calvin-Zyklus");
        assertThat(chunks.get(1).text()).isEqualTo("Photosynthese – Lichtreaktion\n\nIm Thylakoid.");
        assertThat(chunks).noneMatch(chunk -> chunk.text().contains("aliases"));
    }

    @Test
    void should_splitLongSectionsAtParagraphs_andCapTheNumberOfChunks() {
        var paragraph = "Satz über Licht. ".repeat(40);
        var text = "# Lang\n\n" + (paragraph + "\n\n").repeat(10);

        var chunks = NoteChunker.chunks("Lang", text);

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(NoteChunker.MAX_CHARS + 100));
        assertThat(NoteChunker.chunks("Riesig", ("# T\n\n" + paragraph + "\n\n").repeat(500))).hasSize(NoteChunker.MAX_CHUNKS);
    }

    @Test
    void should_stillEmbedTheTitle_ofAnEmptyNote() {
        assertThat(NoteChunker.chunks("Leer", "")).singleElement().satisfies(chunk -> assertThat(chunk.text()).isEqualTo("Leer"));
    }
}
