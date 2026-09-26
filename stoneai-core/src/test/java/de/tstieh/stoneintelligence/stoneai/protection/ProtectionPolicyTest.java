package de.tstieh.stoneintelligence.stoneai.protection;

import de.tstieh.stoneintelligence.stoneai.config.ConfigSchema;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProtectionPolicyTest {

    @TempDir
    Path root;

    private StoneAiConfig config;

    @BeforeEach
    void setUp() {
        config = StoneAiConfig.defaults();
    }

    private ProtectionPolicy policy() {
        return ProtectionPolicy.of(config, root);
    }

    private Path file(String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    @Nested
    @DisplayName("Protection by file name")
    class ByFileName {

        @Test
        @DisplayName("should protect a file whose name carries a marker")
        void should_protect_when_nameContainsMarker() throws IOException {
            Path marked = file("Steuerbescheid [noai].pdf", "x");

            ProtectionDecision decision = policy().inspect(marked);

            assertThat(decision.isProtected()).isTrue();
            assertThat(decision.reason()).contains("[noai]");
        }

        @Test
        @DisplayName("should match markers regardless of case")
        void should_protect_when_markerHasDifferentCase() throws IOException {
            Path marked = file("Vertrag [NOAI].pdf", "x");

            assertThat(policy().inspect(marked).isProtected()).isTrue();
        }

        @Test
        @DisplayName("should protect everything inside a folder whose name carries a marker")
        void should_protect_when_parentFolderIsMarked() throws IOException {
            Path inside = file("Privat [noai]/Brief.pdf", "x");

            assertThat(policy().inspect(inside).isProtected()).isTrue();
        }

        @Test
        @DisplayName("should leave an ordinary file alone")
        void should_allow_when_nothingMatches() throws IOException {
            Path plain = file("Skript.pdf", "x");

            ProtectionDecision decision = policy().inspect(plain);

            assertThat(decision.isProtected()).isFalse();
            assertThat(decision.reason()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Protection by tag inside the document")
    class ByTag {

        @Test
        @DisplayName("should protect a note carrying the tag in its frontmatter")
        void should_protect_when_frontmatterListsTheTag() throws IOException {
            Path note = file("Notiz.md", """
                    ---
                    title: Geheim
                    tags: [privat, NoStoneAI]
                    ---

                    Inhalt
                    """);

            assertThat(policy().inspect(note).isProtected()).isTrue();
        }

        @Test
        @DisplayName("should protect a note carrying the tag inline in the body")
        void should_protect_when_bodyContainsInlineTag() throws IOException {
            Path note = file("Notiz.md", "Text\n\n#NoStoneAI\n\nmehr Text\n");

            assertThat(policy().inspect(note).isProtected()).isTrue();
        }

        @Test
        @DisplayName("should honour a renamed protection tag from the configuration")
        void should_useConfiguredTag_when_tagWasRenamed() throws IOException {
            ConfigSchema.byPath("protection.excludeTag").set(config, "FingerWeg");
            Path note = file("Notiz.md", "Text\n#FingerWeg\n");

            assertThat(policy().inspect(note).isProtected()).isTrue();
        }

        @Test
        @DisplayName("should not be fooled by a tag that is only a prefix of another tag")
        void should_allow_when_tagIsOnlyAPrefix() throws IOException {
            Path note = file("Notiz.md", "Text\n#NoStoneAIExtra\n");

            assertThat(policy().inspect(note).isProtected()).isFalse();
        }

        @Test
        @DisplayName("should not treat a mention of the tag word without a hash as protection")
        void should_allow_when_tagAppearsAsPlainWord() throws IOException {
            Path note = file("Notiz.md", "Hier steht das Wort NoStoneAI ohne Raute.\n");

            assertThat(policy().inspect(note).isProtected()).isFalse();
        }
    }

    @Nested
    @DisplayName("Protection by ignore file")
    class ByIgnoreFile {

        @Test
        @DisplayName("should protect files matching a pattern from .stoneaiignore")
        void should_protect_when_patternMatches() throws IOException {
            file(".stoneaiignore", "*.pdf\n");
            Path pdf = file("Skript.pdf", "x");
            Path note = file("Notiz.md", "x");

            assertThat(policy().inspect(pdf).isProtected()).isTrue();
            assertThat(policy().inspect(note).isProtected()).isFalse();
        }

        @Test
        @DisplayName("should apply an ignore file only from its own folder downwards")
        void should_limitScope_when_ignoreFileIsNested() throws IOException {
            file("Privat/.stoneaiignore", "**\n");
            Path inside = file("Privat/Brief.pdf", "x");
            Path outside = file("Offen/Brief.pdf", "x");

            assertThat(policy().inspect(inside).isProtected()).isTrue();
            assertThat(policy().inspect(outside).isProtected()).isFalse();
        }

        @Test
        @DisplayName("should let a later negation rule re-include a file")
        void should_reInclude_when_negationMatches() throws IOException {
            file(".stoneaiignore", "*.pdf\n!Skript.pdf\n");
            Path allowed = file("Skript.pdf", "x");
            Path blocked = file("Anderes.pdf", "x");

            assertThat(policy().inspect(allowed).isProtected()).isFalse();
            assertThat(policy().inspect(blocked).isProtected()).isTrue();
        }

        @Test
        @DisplayName("should ignore comments and blank lines in the ignore file")
        void should_skipComments_when_readingIgnoreFile() throws IOException {
            file(".stoneaiignore", "# nur Kommentar\n\n");
            Path plain = file("Skript.pdf", "x");

            assertThat(policy().inspect(plain).isProtected()).isFalse();
        }

        @Test
        @DisplayName("should protect a whole folder given as a directory pattern")
        void should_protectFolder_when_patternIsADirectory() throws IOException {
            file(".stoneaiignore", "Vertraulich/\n");
            Path inside = file("Vertraulich/tief/Datei.pdf", "x");

            assertThat(policy().inspect(inside).isProtected()).isTrue();
        }
    }

    @Nested
    @DisplayName("Always excluded paths")
    class AlwaysExcluded {

        @Test
        @DisplayName("should never read Obsidian's own configuration folder")
        void should_protect_when_pathIsInsideObsidianFolder() throws IOException {
            Path internal = file(".obsidian/plugins/foo/data.json", "{}");

            assertThat(policy().inspect(internal).isProtected()).isTrue();
        }

        @Test
        @DisplayName("should protect the ignore file itself")
        void should_protect_when_fileIsTheIgnoreFile() throws IOException {
            Path ignore = file(".stoneaiignore", "*.pdf\n");

            assertThat(policy().inspect(ignore).isProtected()).isTrue();
        }
    }

    @Nested
    @DisplayName("Turning protection off")
    class Disabling {

        @Test
        @DisplayName("should stop matching filename markers once the marker list is emptied")
        void should_allow_when_markersAreCleared() throws IOException {
            ConfigSchema.byPath("protection.filenameMarkers").set(config, "");
            Path marked = file("Datei [noai].pdf", "x");

            assertThat(policy().inspect(marked).isProtected()).isFalse();
        }
    }
}
