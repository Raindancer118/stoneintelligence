package de.tstieh.stoneintelligence.stoneai.vault;

import de.tstieh.stoneintelligence.stoneai.config.ConfigSchema;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.source.DocumentKind;
import de.tstieh.stoneintelligence.stoneai.source.Page;
import de.tstieh.stoneintelligence.stoneai.source.SourceDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceNoteWriterTest {

    private final InMemoryNoteStore store = new InMemoryNoteStore();
    private StoneAiConfig config;

    @BeforeEach
    void setUp() {
        config = StoneAiConfig.defaults();
        ConfigSchema.byPath("vault.path").set(config, "/vault");
    }

    private SourceNoteWriter writer() {
        return new SourceNoteWriter(config, () -> LocalDate.of(2026, 11, 23), false, store);
    }

    private static SourceDocument document(String file, String title, char hash) {
        return new SourceDocument(Path.of("/upload/" + file), title, DocumentKind.PDF,
                List.of(new Page(1, "Text", false)), List.of(), false, String.valueOf(hash).repeat(64));
    }

    @Test
    @DisplayName("should give a second document with the same title its own source note")
    void should_writeSeparateNotes_when_twoDocumentsShareATitle() throws Exception {
        SourceDocument first = document("beschaffung.pdf", "Folien", 'a');
        SourceDocument second = document("produktion.pdf", "Folien", 'b');

        Path firstFile = writer().write(first, List.of("[[Bestellmenge]]"), null);
        Path secondFile = writer().write(second, List.of("[[Losgröße]]"), null);

        assertThat(secondFile).isNotEqualTo(firstFile);
        assertThat(secondFile.getFileName().toString()).isEqualTo("Folien (2).md");
        assertThat(store.read(firstFile)).contains("[[Bestellmenge]]").doesNotContain("[[Losgröße]]");
        assertThat(store.read(secondFile)).contains("[[Losgröße]]").doesNotContain("[[Bestellmenge]]");
        assertThat(writer().linkFor(second)).isEqualTo("[[Quellen/Folien (2)]]");
    }

    @Test
    @DisplayName("should update the same source note when the same document is processed again")
    void should_reuseNote_when_sameDocumentIsProcessedAgain() throws Exception {
        SourceDocument document = document("beschaffung.pdf", "Folien", 'a');
        writer().write(document("produktion.pdf", "Folien", 'b'), List.of(), null);
        Path second = writer().write(document, List.of("[[Alt]]"), null);

        Path again = writer().write(document, List.of("[[Neu]]"), null);

        assertThat(again).isEqualTo(second);
        assertThat(store.read(again)).contains("[[Neu]]").doesNotContain("[[Alt]]");
    }

    @Test
    @DisplayName("should take over a note a person created under that name without a source block")
    void should_useNote_when_existingNoteHasNoSourceBlock() throws Exception {
        Path planned = writer().fileFor(document("x.pdf", "Folien", 'a'));
        store.notes.put(planned, "Meine Gedanken zu den Folien.\n");

        Path written = writer().write(document("x.pdf", "Folien", 'a'), List.of(), null);

        assertThat(written).isEqualTo(planned);
        assertThat(store.read(written)).startsWith("---").contains("Meine Gedanken zu den Folien.");
    }
}
