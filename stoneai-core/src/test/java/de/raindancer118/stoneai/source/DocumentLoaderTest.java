package de.raindancer118.stoneai.source;

import de.raindancer118.stoneai.config.StoneAiConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentLoaderTest {

    @TempDir
    Path dir;

    private final StoneAiConfig config = StoneAiConfig.defaults();

    @Nested
    @DisplayName("PDFs with a text layer")
    class TextPdfs {

        @Test
        @DisplayName("should read every page and keep its page number")
        void should_readPagesWithNumbers_when_pdfHasText() throws Exception {
            Path pdf = textPdf("Kapitel eins über Relationen", "Kapitel zwei über Gruppen");

            SourceDocument document = load(pdf);

            assertThat(document.pages()).hasSize(2);
            assertThat(document.pages().get(0).number()).isEqualTo(1);
            assertThat(document.pages().get(0).text()).contains("Relationen");
            assertThat(document.pages().get(1).number()).isEqualTo(2);
            assertThat(document.pages().get(1).text()).contains("Gruppen");
            assertThat(document.pages()).allMatch(page -> !page.fromOcr());
        }

        @Test
        @DisplayName("should stop after the configured page limit instead of running up a bill")
        void should_truncate_when_documentExceedsMaxPages() throws Exception {
            Path pdf = textPdf("Seite eins", "Seite zwei", "Seite drei");
            config.ingest().maxPages(2);

            SourceDocument document = load(pdf);

            assertThat(document.pages()).hasSize(2);
            assertThat(document.truncated()).isTrue();
        }

        @Test
        @DisplayName("should derive a readable title from the file name")
        void should_deriveTitle_when_pdfHasNoMetadataTitle() throws Exception {
            Path pdf = textPdf("Inhalt");
            Path renamed = pdf.resolveSibling("DM2_Skript Kapitel 3.pdf");
            Files.move(pdf, renamed);

            assertThat(load(renamed).title()).isEqualTo("DM2_Skript Kapitel 3");
        }

        @Test
        @DisplayName("should ignore a placeholder title an exporter wrote into the metadata")
        void should_fallBackToFileName_when_metadataTitleIsAPlaceholder() throws Exception {
            Path pdf = textPdf("Inhalt des Dokuments");
            Path renamed = pdf.resolveSibling("Modulbeschreibung PSP.pdf");
            Files.move(pdf, renamed);
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(renamed.toFile())) {
                document.getDocumentInformation().setTitle("(anonymous)");
                document.save(renamed.toFile());
            }

            assertThat(load(renamed).title()).isEqualTo("Modulbeschreibung PSP");
        }

        @Test
        @DisplayName("should strip the Word export prefix from a metadata title")
        void should_cleanMetadataTitle_when_itComesFromWord() throws Exception {
            Path pdf = textPdf("Inhalt des Dokuments");
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                document.getDocumentInformation().setTitle("Microsoft Word - Skript Kapitel 3.docx");
                document.save(pdf.toFile());
            }

            assertThat(load(pdf).title()).isEqualTo("Skript Kapitel 3");
        }

        @Test
        @DisplayName("should hash the file so the same document is recognised again")
        void should_computeStableHash_when_loadingTwice() throws Exception {
            Path pdf = textPdf("Inhalt");

            assertThat(load(pdf).sha256()).isEqualTo(load(pdf).sha256()).hasSize(64);
        }
    }

    @Nested
    @DisplayName("Scanned PDFs")
    class ScannedPdfs {

        @Test
        @DisplayName("should send a page without a text layer through OCR")
        void should_useOcr_when_pageHasNoText() throws Exception {
            Path pdf = scannedPdf();
            RecordingOcr ocr = new RecordingOcr("Text aus dem Scan");

            SourceDocument document = new PdfLoader(config, ocr).load(pdf);

            assertThat(ocr.calls()).isEqualTo(1);
            assertThat(document.pages()).hasSize(1);
            assertThat(document.pages().get(0).text()).isEqualTo("Text aus dem Scan");
            assertThat(document.pages().get(0).fromOcr()).isTrue();
        }

        // One diagram slide the image model cannot read (provider overloaded) must not cost the
        // other 199 - the page is named as unread instead.
        @Test
        @DisplayName("should go on without an image page the vision model could not read")
        void should_markPageUnreadable_when_ocrFails() throws Exception {
            Path pdf = scannedPdf();
            appendTextPage(pdf, "Seite mit Text");
            OcrService failing = (png, page) -> {
                throw new IllegalStateException("kein Provider konnte antworten: HTTP 503");
            };

            SourceDocument document = new PdfLoader(config, failing).load(pdf);

            assertThat(document.pages()).extracting(Page::number).containsExactly(2);
            assertThat(document.unreadablePages()).containsExactly(1);
        }

        // Out of quota is not one bad page: every further page would fail the same way, and a run
        // that quietly drops them looks finished. The whole run waits for capacity instead.
        @Test
        @DisplayName("should stop when the vision model is out of capacity instead of dropping the page")
        void should_propagate_when_ocrIsOutOfCapacity() throws Exception {
            Path pdf = scannedPdf();
            appendTextPage(pdf, "Seite mit Text");
            java.time.Instant back = java.time.Instant.parse("2026-09-26T07:00:00Z");
            OcrService exhausted = (png, page) -> {
                throw new de.raindancer118.stoneai.extract.LlmCapacityException("gemini: Tageskontingent aufgebraucht", back);
            };

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PdfLoader(config, exhausted).load(pdf))
                    .isInstanceOfSatisfying(de.raindancer118.stoneai.extract.LlmCapacityException.class,
                            e -> assertThat(e.availableAgainAt()).isEqualTo(back));
        }

        // A document that is nothing but a scan would come out empty - better to try again later.
        @Test
        @DisplayName("should fail when no page at all could be read")
        void should_fail_when_everyPageIsUnreadable() throws Exception {
            Path pdf = scannedPdf();
            OcrService failing = (png, page) -> {
                throw new IllegalStateException("kein Provider konnte antworten: HTTP 503");
            };

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PdfLoader(config, failing).load(pdf))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should skip an image page rather than fail when vision is switched off")
        void should_skipPage_when_visionIsDisabled() throws Exception {
            Path pdf = scannedPdf();
            config.llm().visionEnabled(false);
            RecordingOcr ocr = new RecordingOcr("unused");

            SourceDocument document = new PdfLoader(config, ocr).load(pdf);

            assertThat(ocr.calls()).isZero();
            assertThat(document.pages()).isEmpty();
            assertThat(document.skippedPages()).containsExactly(1);
        }
    }

    @Nested
    @DisplayName("Markdown and plain text")
    class TextFiles {

        @Test
        @DisplayName("should strip the frontmatter but keep the body")
        void should_dropFrontmatter_when_loadingMarkdown() throws Exception {
            Path note = write("Notiz.md", """
                    ---
                    title: Meine Notiz
                    tags: [uni]
                    ---

                    # Überschrift

                    Inhalt mit [[Wikilink]].
                    """);

            SourceDocument document = load(note);

            assertThat(document.pages()).hasSize(1);
            assertThat(document.pages().get(0).text())
                    .doesNotContain("tags:")
                    .contains("# Überschrift")
                    .contains("[[Wikilink]]");
        }

        @Test
        @DisplayName("should prefer the frontmatter title over the file name")
        void should_useFrontmatterTitle_when_present() throws Exception {
            Path note = write("dateiname.md", "---\ntitle: Echter Titel\n---\n\nInhalt\n");

            assertThat(load(note).title()).isEqualTo("Echter Titel");
        }

        @Test
        @DisplayName("should read a plain text file as a single page")
        void should_readSinglePage_when_fileIsPlainText() throws Exception {
            Path text = write("notizen.txt", "Erste Zeile\nZweite Zeile\n");

            assertThat(load(text).pages()).hasSize(1);
            assertThat(load(text).pages().get(0).text()).contains("Zweite Zeile");
        }
    }

    @Nested
    @DisplayName("Choosing a loader")
    class Dispatch {

        @Test
        @DisplayName("should reject a file type nothing can read")
        void should_throw_when_noLoaderSupportsTheFile() throws Exception {
            Path binary = write("bild.png", "not really a png");

            assertThatThrownBy(() -> load(binary))
                    .isInstanceOf(UnsupportedDocumentException.class)
                    .hasMessageContaining("bild.png");
        }

        @Test
        @DisplayName("should report an empty document instead of pretending it read something")
        void should_throw_when_documentHasNoUsableText() throws Exception {
            Path empty = write("leer.md", "---\ntitle: Leer\n---\n\n   \n");

            assertThatThrownBy(() -> load(empty))
                    .isInstanceOf(EmptyDocumentException.class);
        }
    }

    private SourceDocument load(Path file) throws IOException {
        return DocumentLoaders.forConfig(config, new RecordingOcr("ocr")).load(file);
    }

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private Path textPdf(String... pageTexts) throws IOException {
        Path file = dir.resolve("dokument.pdf");
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(60, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    private Path scannedPdf() throws IOException {
        Path file = dir.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage image = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 120, 60);
            graphics.setColor(Color.BLACK);
            graphics.drawString("scan", 10, 30);
            graphics.dispose();
            PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(xObject, 60, 600, 120, 60);
            }
            document.save(file.toFile());
        }
        return file;
    }

    private static void appendTextPage(Path pdf, String text) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(60, 700);
                content.showText(text);
                content.endText();
            }
            document.save(pdf.toFile());
        }
    }

    /** Stands in for the vision model: records every call and returns a fixed transcription. */
    private static final class RecordingOcr implements OcrService {

        private final String result;
        private final List<Integer> pages = new ArrayList<>();

        RecordingOcr(String result) {
            this.result = result;
        }

        int calls() {
            return pages.size();
        }

        @Override
        public String read(byte[] pngImage, int pageNumber) {
            pages.add(pageNumber);
            return result;
        }
    }
}
