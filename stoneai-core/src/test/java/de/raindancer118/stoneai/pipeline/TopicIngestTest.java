package de.raindancer118.stoneai.pipeline;

import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;
import de.raindancer118.stoneai.vault.InMemoryNoteStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notes are written about the topics a document is actually about - the case, the people, the
 * characters - with the details folded in, not one note per term the text happens to mention.
 * And every link the run writes leads to a note that exists.
 */
class TopicIngestTest {

    private static final Path VAULT = Path.of("/vault");
    private static final String PLAN = """
            {"topics":[
              {"title":"Verkehrsunfall am 5. Juni 2026","kind":"ereignis","scope":"Hergang, Schaden, Versicherung"},
              {"title":"Merle Wilkens","kind":"person","scope":"Zeugin, Kontakt"}]}
            """;
    private static final String NOTES = """
            {"concepts":[
              {"title":"Verkehrsunfall am 5. Juni 2026","definition":"Auffahrunfall beim Einparken.",
               "body":"Beim Einparken fuhr ein BMW auf. Zeugin war [[Merle Wilkens]], siehe auch [[Unfallhergang]].",
               "related":["Merle Wilkens","Unfallhergang"],"confidence":0.9},
              {"title":"Merle Wilkens","definition":"Beifahrerin und Zeugin.",
               "body":"- **Telefon:** 0123 456","related":["Verkehrsunfall am 5. Juni 2026"],"confidence":0.9},
              {"title":"Schadennummer","definition":"Aktenzeichen der Versicherung.",
               "body":"Schadennummer 2261-069.393/1-446.","confidence":0.8}]}
            """;

    @TempDir
    Path upload;

    private StoneAiConfig config;
    private final InMemoryNoteStore store = new InMemoryNoteStore();
    private final List<String> plannerPrompts = new ArrayList<>();
    private String planAnswer = PLAN;
    private String notesAnswer = NOTES;
    private int tokensPerCall = 10;
    private boolean visionDown;

    @BeforeEach
    void setUp() {
        config = StoneAiConfig.defaults();
        ConfigSchema.byPath("vault.path").set(config, VAULT.toString());
    }

    private IngestReport ingest(Path document) throws IOException {
        LlmClient llm = new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                if (system.contains("Themenplan")) {
                    plannerPrompts.add(user);
                    return new LlmAnswer(planAnswer, 10, "fake/smart");
                }
                return new LlmAnswer(system.startsWith("Du führst") ? "" : notesAnswer, tokensPerCall, "fake/fast");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                if (visionDown) {
                    throw new IllegalStateException("kein Provider konnte antworten: HTTP 503");
                }
                return new LlmAnswer("", 0, "fake/vision");
            }
        };
        return IngestPipeline.hosted(config, llm, () -> LocalDate.of(2026, 9, 23)).ingestInto(document, store);
    }

    private Path markdown(String name, String text) throws IOException {
        Path document = upload.resolve(name);
        Files.writeString(document, text);
        return document;
    }

    private String note(String relative) {
        return store.notes.get(VAULT.resolve(relative));
    }

    @Test
    @DisplayName("should write one note per planned topic and fold everything else into the main topic")
    void should_writeNotesAboutTopics_notAboutEveryTerm() throws IOException {
        ingest(markdown("Brief.md", "# Unfall\n\nAm 5. Juni 2026 fuhr ein BMW auf.\n"));

        assertThat(store.notes.keySet()).filteredOn(file -> file.startsWith(VAULT.resolve("Notizen")))
                .containsExactlyInAnyOrder(VAULT.resolve("Notizen/Verkehrsunfall am 5. Juni 2026.md"),
                        VAULT.resolve("Notizen/Merle Wilkens.md"));
        assertThat(note("Notizen/Verkehrsunfall am 5. Juni 2026.md")).contains("2261-069.393/1-446");
    }

    // Two letters about the same accident belong in the same note, not in two near-duplicates.
    @Test
    @DisplayName("should show the planner the notes the vault already has")
    void should_offerExistingTitlesToThePlanner() throws IOException {
        store.notes.put(VAULT.resolve("Notizen/Opel Werkstatt Dello.md"), "---\ntitle: Opel Werkstatt Dello\n---\n\nx\n");

        ingest(markdown("Brief.md", "# Unfall\n\nText.\n"));

        assertThat(plannerPrompts).singleElement().asString().contains("Opel Werkstatt Dello");
    }

    @Test
    @DisplayName("should never link to a note that does not exist")
    void should_linkOnlyToNotesThatExist() throws IOException {
        ingest(markdown("Brief.md", "# Unfall\n\nText.\n"));

        String accident = note("Notizen/Verkehrsunfall am 5. Juni 2026.md");
        assertThat(accident).contains("[[Merle Wilkens]]").contains("Unfallhergang").doesNotContain("[[Unfallhergang]]");
        assertEveryLinkResolves();
    }

    // Obsidian resolves a link by file name - a note written earlier as "Fluss-im-Wald.md" is
    // reached through its file name, however its title is spelled.
    @Test
    @DisplayName("should link a note by the file it actually lives in")
    void should_linkByFileName_whenAnExistingNoteIsExtended() throws IOException {
        store.notes.put(VAULT.resolve("Notizen/Merle-Wilkens.md"), "---\ntitle: Merle Wilkens\n---\n\nalt\n");
        planAnswer = PLAN.replace("Merle Wilkens", "Merle‑Wilkens");

        ingest(markdown("Brief.md", "# Unfall\n\nText.\n"));

        assertThat(note("Notizen/Merle-Wilkens.md")).contains("0123 456");
        assertThat(note("Quellen/Brief.md")).contains("[[Merle-Wilkens|Merle Wilkens]]");
        assertEveryLinkResolves();
    }

    @Test
    @DisplayName("should keep the uploaded original in the vault and link it from the source note")
    void should_storeAndLinkTheOriginal() throws IOException {
        Path pdf = pdf("Brief_LVM.pdf", "Am 5. Juni 2026 fuhr ein BMW auf.");

        ingest(pdf);

        assertThat(store.attachments).containsEntry(VAULT.resolve("Anhänge/Brief_LVM.pdf"), Files.readAllBytes(pdf));
        assertThat(note("Quellen/Brief_LVM.md")).contains("![[Brief_LVM.pdf]]");
        assertEveryLinkResolves();
    }

    // Issue #1: die Quellenangabe oeffnet das Original an der Seite, von der der Inhalt stammt.
    @Test
    @DisplayName("should link the citation of a note to the page of the stored original")
    void should_linkCitationsToTheOriginalPage() throws IOException {
        ingest(pdf("Brief_LVM.pdf", "Am 5. Juni 2026 fuhr ein BMW auf."));

        assertThat(note("Notizen/Verkehrsunfall am 5. Juni 2026.md"))
                .contains("*Quelle: [[Anhänge/Brief_LVM.pdf#page=1|Brief_LVM, S. 1]]*");
        assertEveryLinkResolves();
    }

    // Issue #1: in den Eigenschaften nur noch, was man beim Lesen braucht - der Rest steht im Text.
    @Test
    @DisplayName("should keep the source note's properties short and say the rest in the text")
    void should_keepSourceNotePropertiesShort() throws IOException {
        ingest(pdf("Brief_LVM.pdf", "Am 5. Juni 2026 fuhr ein BMW auf."));

        String source = note("Quellen/Brief_LVM.md");
        assertThat(de.raindancer118.stoneai.vault.Frontmatter.of(source).frontmatter().keys())
                .containsExactly("tags", "created", "updated");
        assertThat(source).contains("1 Seite");
    }

    // A text upload is already readable as a note - copying it would only add a duplicate.
    @Test
    @DisplayName("should not copy a markdown source into the vault")
    void should_notCopyMarkdownSources() throws IOException {
        ingest(markdown("Brief.md", "# Unfall\n\nText.\n"));

        assertThat(store.attachments).isEmpty();
    }

    @Test
    @DisplayName("should fall back to one note about the document when no plan comes back")
    void should_writeOneNote_whenThePlannerFails() throws IOException {
        planAnswer = "Das kann ich nicht.";
        notesAnswer = """
                {"concepts":[{"title":"Irgendwas","body":"Inhalt des Briefs.","confidence":0.8}]}""";

        ingest(markdown("Brief an die Versicherung.md", "# Brief an die Versicherung\n\nText.\n"));

        assertThat(store.notes.keySet()).filteredOn(file -> file.startsWith(VAULT.resolve("Notizen")))
                .containsExactly(VAULT.resolve("Notizen/Brief an die Versicherung.md"));
        assertThat(note("Notizen/Brief an die Versicherung.md")).contains("Inhalt des Briefs.");
    }

    @Test
    @DisplayName("should keep using a source note written under the old hyphenated file name")
    void should_reuseTheLegacySourceNote() throws IOException {
        store.notes.put(VAULT.resolve("Quellen/Brief-an-die-Versicherung.md"), "---\ntitle: Brief an die Versicherung\n---\n\nalt\n");

        ingest(markdown("Brief an die Versicherung.md", "# Brief an die Versicherung\n\nText.\n"));

        assertThat(store.notes).doesNotContainKey(VAULT.resolve("Quellen/Brief an die Versicherung.md"));
        assertThat(note("Quellen/Brief-an-die-Versicherung.md")).contains("[[Merle Wilkens]]");
    }

    // A 200-slide deck used to stop at the token budget and still report success.
    @Test
    @DisplayName("should say in the report and in the source note which parts were not read")
    void should_reportWhatTheBudgetLeftUnread() throws IOException {
        ConfigSchema.byPath("llm.maxTokensPerRun").set(config, "1000");
        ConfigSchema.byPath("llm.maxTokensPerPage").set(config, "0");
        ConfigSchema.byPath("llm.parallelCalls").set(config, "1");
        tokensPerCall = 1_000;
        String section = "Inhalt ".repeat(1_200);

        IngestReport report = ingest(markdown("Skript.md",
                "# Kapitel 1\n\n" + section + "\n\n# Kapitel 2\n\n" + section + "\n\n# Kapitel 3\n\n" + section));

        assertThat(report.budgetExhausted()).isTrue();
        assertThat(report.unprocessed()).containsExactly("Skript — Kapitel 2", "Skript — Kapitel 3");
        assertThat(note("Quellen/Skript.md")).contains("Nicht verarbeitet").contains("Skript — Kapitel 2");
    }

    // Ein Lehrbuch brach nach etwa 170 dichten Seiten am festen Budget ab.
    @Test
    @DisplayName("should give a long document a budget that grows with its length")
    void should_readALongDocumentToTheEnd() throws IOException {
        ConfigSchema.byPath("llm.maxTokensPerRun").set(config, "1000");
        ConfigSchema.byPath("llm.maxTokensPerPage").set(config, "3000");
        tokensPerCall = 1_000;
        String section = "Inhalt ".repeat(1_200);
        StringBuilder book = new StringBuilder();
        for (int chapter = 1; chapter <= 12; chapter++) {
            book.append("# Kapitel ").append(chapter).append("\n\n").append(section).append("\n\n");
        }

        IngestReport report = ingest(markdown("Lehrbuch.md", book.toString()));

        assertThat(report.budgetExhausted()).isFalse();
        assertThat(report.unprocessed()).isEmpty();
    }

    @Test
    @DisplayName("should name an image slide the vision model could not read")
    void should_reportUnreadableImagePages() throws IOException {
        visionDown = true;
        Path deck = pdf("Folien.pdf", "Folie mit Text");
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(deck.toFile())) {
            PDPage page = new PDPage();
            document.addPage(page);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(120, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var xObject = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(xObject, 60, 600, 120, 60);
            }
            document.save(deck.toFile());
        }

        IngestReport report = ingest(deck);

        assertThat(report.gaps()).contains("Folien, S. 2 (Bild nicht lesbar)");
        assertThat(note("Quellen/Folien.md")).contains("Folien, S. 2 (Bild nicht lesbar)");
    }

    private void assertEveryLinkResolves() {
        List<String> names = new ArrayList<>();
        store.notes.keySet().forEach(file -> names.add(file.getFileName().toString().replaceFirst("\\.md$", "")));
        store.attachments.keySet().forEach(file -> names.add(file.getFileName().toString()));
        Matcher links = Pattern.compile("\\[\\[([^\\]|#]+)").matcher(String.join("\n", store.notes.values()));
        while (links.find()) {
            String target = links.group(1);
            String name = target.contains("/") ? target.substring(target.lastIndexOf('/') + 1) : target;
            assertThat(names).as("link [[%s]]", target).contains(name);
        }
    }

    private Path pdf(String name, String text) throws IOException {
        Path file = upload.resolve(name);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(60, 700);
                content.showText(text);
                content.endText();
            }
            document.save(file.toFile());
        }
        return file;
    }
}
