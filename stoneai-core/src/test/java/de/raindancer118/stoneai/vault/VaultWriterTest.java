package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.chunk.Provenance;
import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.note.DraftNote;
import de.raindancer118.stoneai.protection.ProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VaultWriterTest {

    @TempDir
    Path vault;

    private StoneAiConfig config;

    @BeforeEach
    void setUp() {
        config = StoneAiConfig.defaults();
        ConfigSchema.byPath("vault.path").set(config, vault.toString());
    }

    private VaultWriter writer() {
        return new VaultWriter(config, ProtectionPolicy.of(config, vault),
                () -> LocalDate.of(2026, 9, 1));
    }

    private static DraftNote note(String title, String body) {
        return new DraftNote(title, List.of("Alias"), "Kurze Definition.", body,
                List.of("mathematik/relationen"), Map.of("begriff", List.of("Reflexivität")),
                List.of(), 0.86,
                List.of(new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 42, null)));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final String documentHash = sha256("dokument");

    @Nested
    @DisplayName("Creating a new note")
    class Creating {

        @Test
        @DisplayName("should write the note into the configured notes folder")
        void should_createFile_when_noteIsNew() throws IOException {
            WriteResult result = writer().write(note("Äquivalenzrelation", "Der Inhalt."), documentHash,
                    "[[Quellen/Skript]]");

            assertThat(result.outcome()).isEqualTo(WriteResult.Outcome.CREATED);
            Path file = config.vault().notesDir().resolve("Äquivalenzrelation.md");
            assertThat(file).exists();
            assertThat(Files.readString(file)).contains("Der Inhalt.");
        }

        @Test
        @DisplayName("should carry the StoneAI tag both in the frontmatter and inline")
        void should_markAsAiWritten_when_creatingANote() throws IOException {
            writer().write(note("Gruppe", "Inhalt."), documentHash, "[[Quellen/Skript]]");

            String content = Files.readString(config.vault().notesDir().resolve("Gruppe.md"));
            Frontmatter.Document document = Frontmatter.of(content);

            assertThat(document.frontmatter().list("tags")).contains("StoneAI");
            assertThat(document.body()).contains("#StoneAI");
        }

        // Issue #1: die Eigenschaften oben verwirrten beim Lesen. Was Obsidian selbst nutzt
        // (Tags, Aliasse) und die Daten bleiben, Herkunft steht verlinkt im Text, der Rest entfaellt.
        @Test
        @DisplayName("should keep only the properties Obsidian itself uses")
        void should_writeOnlyTagsAndAliases_when_creatingANote() throws IOException {
            writer().write(note("Gruppe", "Inhalt."), documentHash, "[[Quellen/Skript]]");

            Frontmatter frontmatter = Frontmatter.of(
                    Files.readString(config.vault().notesDir().resolve("Gruppe.md"))).frontmatter();

            assertThat(frontmatter.keys()).containsExactly("aliases", "tags", "created", "updated");
            assertThat(frontmatter.list("aliases")).containsExactly("Alias");
            assertThat(frontmatter.scalar("created")).isEqualTo("2026-09-01");
        }

        @Test
        @DisplayName("should leave out empty aliases")
        void should_omitAliases_when_thereAreNone() throws IOException {
            DraftNote plain = new DraftNote("Gruppe", List.of(), "", "Inhalt.", List.of(), Map.of(), List.of(), 0.9,
                    List.of(new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 1, null)));

            writer().write(plain, documentHash, "[[Quellen/Skript]]");

            assertThat(Frontmatter.of(Files.readString(config.vault().notesDir().resolve("Gruppe.md")))
                    .frontmatter().keys()).containsExactly("tags", "created", "updated");
        }

        @Test
        @DisplayName("should honour a renamed tag from the configuration")
        void should_useConfiguredTag_when_tagWasRenamed() throws IOException {
            ConfigSchema.byPath("notes.tag").set(config, "KIgeneriert");

            writer().write(note("Gruppe", "Inhalt."), documentHash, "[[Q]]");

            String content = Files.readString(config.vault().notesDir().resolve("Gruppe.md"));
            assertThat(content).contains("#KIgeneriert").doesNotContain("#StoneAI");
        }

        @Test
        @DisplayName("should replace characters Obsidian cannot store in a file name")
        void should_sanitiseFileName_when_titleHasIllegalCharacters() throws IOException {
            WriteResult result = writer().write(note("Menge A/B: [Teil] #1", "Inhalt."),
                    documentHash, "[[Q]]");

            assertThat(result.file().getFileName().toString())
                    .doesNotContain("/").doesNotContain("[").doesNotContain("#").doesNotContain(":");
            assertThat(result.file()).exists();
            assertThat(Frontmatter.of(Files.readString(result.file())).frontmatter().scalar("title"))
                    .isEqualTo("Menge A/B: [Teil] #1");
        }

        @Test
        @DisplayName("should leave no temporary files behind")
        void should_writeAtomically_when_creating() throws IOException {
            writer().write(note("Gruppe", "Inhalt."), documentHash, "[[Q]]");

            try (var files = Files.list(config.vault().notesDir())) {
                assertThat(files.map(path -> path.getFileName().toString()))
                        .containsExactly("Gruppe.md");
            }
        }
    }

    @Nested
    @DisplayName("Never overwriting")
    class NeverOverwriting {

        private Path handwritten(String name, String content) throws IOException {
            Path file = config.vault().notesDir().resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return file;
        }

        @Test
        @DisplayName("should keep every byte a person wrote when appending to their note")
        void should_preserveHandwrittenBytes_when_appending() throws IOException {
            String original = """
                    ---
                    title: Gruppe
                    tags: [uni]
                    ---

                    # Gruppe

                    Meine eigene Erklärung, die bleiben muss.
                    """;
            Path file = handwritten("Gruppe.md", original);

            WriteResult result = writer().write(note("Gruppe", "Von der KI ergänzt."),
                    documentHash, "[[Q]]");

            assertThat(result.outcome()).isEqualTo(WriteResult.Outcome.APPENDED);
            String after = Files.readString(file);
            assertThat(after).contains("Meine eigene Erklärung, die bleiben muss.");
            assertThat(ManagedBlock.strip(Frontmatter.of(after).body()))
                    .isEqualTo(Frontmatter.of(original).body());
        }

        @Test
        @DisplayName("should add frontmatter keys without touching the ones already there")
        void should_mergeFrontmatterAdditively_when_appending() throws IOException {
            Path file = handwritten("Gruppe.md", "---\ntitle: Mein Titel\ntags: [uni]\n---\n\nText\n");

            writer().write(note("Gruppe", "Ergänzung."), documentHash, "[[Q]]");

            Frontmatter frontmatter = Frontmatter.of(Files.readString(file)).frontmatter();
            assertThat(frontmatter.scalar("title")).isEqualTo("Mein Titel");
            assertThat(frontmatter.list("tags")).contains("uni", "StoneAI");
        }

        @Test
        @DisplayName("should drop the bookkeeping properties earlier versions wrote into their own notes")
        void should_removeLegacyProperties_when_rewritingAnAiNote() throws IOException {
            Path file = handwritten("Gruppe.md", """
                    ---
                    title: Gruppe
                    aliases: [Alias]
                    tags: [StoneAI, mathe]
                    type: concept
                    source: "[[Quellen/Skript]]"
                    source_page: 42
                    related: [Ring]
                    created: 2026-08-01
                    updated: 2026-08-01
                    confidence: 0.8
                    bewertung: 5
                    ---

                    Text
                    """);

            writer().write(note("Gruppe", "Ergänzung."), documentHash, "[[Q]]");

            Frontmatter frontmatter = Frontmatter.of(Files.readString(file)).frontmatter();
            assertThat(frontmatter.keys()).containsExactly("aliases", "tags", "created", "updated", "bewertung");
            assertThat(frontmatter.scalar("created")).isEqualTo("2026-08-01");
            assertThat(frontmatter.scalar("updated")).isEqualTo("2026-09-01");
            assertThat(frontmatter.list("tags")).contains("mathe", "StoneAI");
        }

        @Test
        @DisplayName("should leave the properties of a person's own note alone")
        void should_keepProperties_when_noteWasNotWrittenByTheAi() throws IOException {
            Path file = handwritten("Gruppe.md", "---\ntitle: Mein Titel\ncreated: 2020-01-01\nsource: Buch\n---\n\nText\n");

            writer().write(note("Gruppe", "Ergänzung."), documentHash, "[[Q]]");

            Frontmatter frontmatter = Frontmatter.of(Files.readString(file)).frontmatter();
            assertThat(frontmatter.scalar("created")).isEqualTo("2020-01-01");
            assertThat(frontmatter.scalar("source")).isEqualTo("Buch");
        }

        @Test
        @DisplayName("should update its own block rather than appending a second one on a re-run")
        void should_replaceOwnBlock_when_runTwice() throws IOException {
            writer().write(note("Gruppe", "Erste Fassung."), documentHash, "[[Q]]");
            writer().write(note("Gruppe", "Zweite Fassung."), documentHash, "[[Q]]");

            String content = Files.readString(config.vault().notesDir().resolve("Gruppe.md"));
            assertThat(content).contains("Zweite Fassung.").doesNotContain("Erste Fassung.");
            assertThat(ManagedBlock.ids(Frontmatter.of(content).body())).hasSize(1);
        }

        @Test
        @DisplayName("should keep the blocks of two different documents apart")
        void should_keepBothBlocks_when_notesComeFromDifferentDocuments() throws IOException {
            writer().write(note("Gruppe", "Aus Dokument A."), documentHash, "[[Q]]");
            writer().write(note("Gruppe", "Aus Dokument B."), sha256("anderes"), "[[Q]]");

            String body = Frontmatter.of(
                    Files.readString(config.vault().notesDir().resolve("Gruppe.md"))).body();
            assertThat(body).contains("Aus Dokument A.").contains("Aus Dokument B.");
            assertThat(ManagedBlock.ids(body)).hasSize(2);
        }

        @Test
        @DisplayName("should leave a protected note completely alone")
        void should_skip_when_existingNoteIsProtected() throws IOException {
            String original = "---\ntitle: Gruppe\ntags: [NoStoneAI]\n---\n\nHände weg.\n";
            Path file = handwritten("Gruppe.md", original);

            WriteResult result = writer().write(note("Gruppe", "Ergänzung."), documentHash, "[[Q]]");

            assertThat(result.outcome()).isEqualTo(WriteResult.Outcome.SKIPPED_PROTECTED);
            assertThat(Files.readString(file)).isEqualTo(original);
        }

        @Test
        @DisplayName("should report the reason a note was skipped instead of failing silently")
        void should_explainSkip_when_noteIsProtected() throws IOException {
            handwritten("Gruppe.md", "---\ntags: [NoStoneAI]\n---\n\nx\n");

            assertThat(writer().write(note("Gruppe", "x"), documentHash, "[[Q]]").reason())
                    .containsIgnoringCase("schutz");
        }
    }

    @Nested
    @DisplayName("Linking")
    class Linking {

        private DraftNote linking(String... related) {
            return new DraftNote("Gruppe", List.of(), "Definition.", "Inhalt.", List.of(),
                    Map.of(), List.of(related), 0.8,
                    List.of(new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 1, null)));
        }

        @Test
        @DisplayName("should link only to notes that actually exist")
        void should_linkExistingNotesOnly_when_rendering() throws IOException {
            VaultIndex index = VaultIndex.empty();
            index.register("Ring", List.of(), config.vault().notesDir().resolve("Ring.md"));
            VaultWriter writer = new VaultWriter(config, ProtectionPolicy.of(config, vault),
                    () -> LocalDate.of(2026, 9, 1), index);

            writer.write(linking("Ring", "Nicht-vorhandenes-Konzept"), documentHash, "[[Q]]");

            String content = Files.readString(config.vault().notesDir().resolve("Gruppe.md"));
            assertThat(content).contains("[[Ring]]");
            assertThat(content).doesNotContain("[[Nicht-vorhandenes-Konzept]]");
        }

        @Test
        @DisplayName("should not name unresolved references anywhere")
        void should_dropUnresolvedReferences_when_linkIsNotWritten() throws IOException {
            VaultWriter writer = new VaultWriter(config, ProtectionPolicy.of(config, vault),
                    () -> LocalDate.of(2026, 9, 1), VaultIndex.empty());

            writer.write(linking("Nicht-vorhandenes-Konzept"), documentHash, "[[Q]]");

            assertThat(Files.readString(config.vault().notesDir().resolve("Gruppe.md")))
                    .doesNotContain("Nicht-vorhandenes-Konzept");
        }

        @Test
        @DisplayName("should keep a link with a display text intact inside a table")
        void should_escapeLinkAlias_when_linkSitsInATableRow() throws IOException {
            VaultIndex index = VaultIndex.empty();
            index.register("Ring", List.of(), config.vault().notesDir().resolve("Ring.md"));
            VaultWriter writer = new VaultWriter(config, ProtectionPolicy.of(config, vault),
                    () -> LocalDate.of(2026, 9, 1), index);
            DraftNote table = new DraftNote("Gruppe", List.of(), "", """
                    | Struktur | Beispiel |
                    |---|---|
                    | [[Ring|Ringe]] | ℤ |

                    Mehr über [[Ring|Ringe]].""", List.of(), Map.of(), List.of(), 0.8,
                    List.of(new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 1, null)));

            writer.write(table, documentHash, "[[Q]]");

            String content = Files.readString(config.vault().notesDir().resolve("Gruppe.md"));
            assertThat(content).contains("| [[Ring\\|Ringe]] | ℤ |").contains("Mehr über [[Ring|Ringe]].");
        }
    }

    // Issue #1: die Quellenangabe fuehrt zur genauen Stelle im Original.
    @Nested
    @DisplayName("Citing the source")
    class Citing {

        private DraftNote from(Provenance... sources) {
            return new DraftNote("Gruppe", List.of(), "", "Inhalt.", List.of(), Map.of(), List.of(), 0.8,
                    List.of(sources));
        }

        private String written() throws IOException {
            return Files.readString(config.vault().notesDir().resolve("Gruppe.md"));
        }

        @Test
        @DisplayName("should link each cited page to that page of the stored original")
        void should_linkToThePdfPage_when_theOriginalIsInTheVault() throws IOException {
            Path original = vault.resolve("Anhänge").resolve("Skript.pdf");

            writer().write(from(new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 42, null),
                            new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 12, null, 18)),
                    documentHash, "[[Quellen/Skript]]", original);

            assertThat(written()).contains(
                    "*Quelle: [[Anhänge/Skript.pdf#page=42|Skript, S. 42]]; [[Anhänge/Skript.pdf#page=12|Skript, S. 12–18]]*");
        }

        @Test
        @DisplayName("should link to the source note when the original is not in the vault")
        void should_linkToTheSourceNote_when_thereIsNoOriginal() throws IOException {
            writer().write(from(new Provenance("Skript", Path.of("/tmp/Skript.pdf"), 42, null)),
                    documentHash, "[[Quellen/Skript]]", null);

            assertThat(written()).contains("*Quelle: [[Quellen/Skript|Skript, S. 42]]*");
        }

        @Test
        @DisplayName("should cite a section of a text document by its heading")
        void should_linkToTheSourceNote_when_theSourceHasNoPages() throws IOException {
            writer().write(from(new Provenance("Mitschrift", Path.of("/tmp/Mitschrift.md"), null, "Relationen")),
                    documentHash, "[[Quellen/Mitschrift]]", vault.resolve("Anhänge").resolve("Mitschrift.md"));

            assertThat(written()).contains("*Quelle: [[Quellen/Mitschrift|Mitschrift — Relationen]]*");
        }
    }

    @Nested
    @DisplayName("Dry runs")
    class DryRuns {

        @Test
        @DisplayName("should report what it would do without writing anything")
        void should_notTouchDisk_when_dryRun() throws IOException {
            WriteResult result = writer().dryRun().write(note("Gruppe", "Inhalt."), documentHash, "[[Q]]");

            assertThat(result.outcome()).isEqualTo(WriteResult.Outcome.CREATED);
            assertThat(config.vault().notesDir().resolve("Gruppe.md")).doesNotExist();
            assertThat(result.preview()).contains("Inhalt.");
        }
    }
}
