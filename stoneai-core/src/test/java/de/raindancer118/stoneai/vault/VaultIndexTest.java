package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.protection.ProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VaultIndexTest {

    @TempDir
    Path vault;

    private StoneAiConfig config;

    @BeforeEach
    void setUp() {
        config = StoneAiConfig.defaults();
        ConfigSchema.byPath("vault.path").set(config, vault.toString());
    }

    private VaultIndex index() throws IOException {
        return VaultIndex.build(config, ProtectionPolicy.of(config, vault));
    }

    private Path note(String relative, String content) throws IOException {
        Path file = vault.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Nested
    @DisplayName("Building the index")
    class Building {

        @Test
        @DisplayName("should find notes anywhere in the vault, not just in the notes folder")
        void should_indexWholeVault_when_built() throws IOException {
            note("Notizen/Gruppe.md", "---\ntitle: Gruppe\n---\n\nx\n");
            note("Uni/Algebra/Ring.md", "# Ring\n");

            assertThat(index().size()).isEqualTo(2);
        }

        @Test
        @DisplayName("should take the title from the frontmatter, else from the file name")
        void should_deriveTitle_when_frontmatterIsMissing() throws IOException {
            note("A.md", "---\ntitle: Echter Titel\n---\n\nx\n");
            note("Dateiname.md", "nur Text\n");

            assertThat(index().titles()).contains("Echter Titel", "Dateiname");
        }

        @Test
        @DisplayName("should not index a note that is protected from the AI")
        void should_skipProtectedNotes_when_building() throws IOException {
            note("Geheim.md", "---\ntags: [NoStoneAI]\n---\n\nx\n");
            note("Offen.md", "x\n");

            assertThat(index().titles()).containsExactly("Offen");
        }

        @Test
        @DisplayName("should ignore Obsidian's own folders")
        void should_skipDotFolders_when_building() throws IOException {
            note(".obsidian/plugins/x/notes.md", "x\n");
            note("Echt.md", "x\n");

            assertThat(index().size()).isEqualTo(1);
        }

        @Test
        @DisplayName("should cope with a vault folder that does not exist yet")
        void should_returnEmpty_when_vaultIsMissing() throws IOException {
            ConfigSchema.byPath("vault.path").set(config, vault.resolve("noch-nicht").toString());

            assertThat(index().size()).isZero();
        }
    }

    @Nested
    @DisplayName("Resolving a title")
    class Resolving {

        @Test
        @DisplayName("should find an existing note by its exact title")
        void should_resolve_when_titleMatchesExactly() throws IOException {
            Path file = note("Notizen/Gruppe.md", "---\ntitle: Gruppe\n---\n\nx\n");

            assertThat(index().resolve("Gruppe", 0.88)).contains(file);
        }

        @Test
        @DisplayName("should find a note through one of its aliases")
        void should_resolve_when_titleMatchesAnAlias() throws IOException {
            Path file = note("Notizen/Halbgruppe.md",
                    "---\ntitle: Halbgruppe\naliases: [Monoid]\n---\n\nx\n");

            assertThat(index().resolve("Monoid", 0.88)).contains(file);
        }

        @Test
        @DisplayName("should find a note whose title differs only in inflection")
        void should_resolve_when_titleIsNearlyIdentical() throws IOException {
            Path file = note("Notizen/Äquivalenzrelation.md", "---\ntitle: Äquivalenzrelation\n---\n\nx\n");

            assertThat(index().resolve("Äquivalenzrelationen", 0.88)).contains(file);
        }

        @Test
        @DisplayName("should not confuse two genuinely different notes")
        void should_returnEmpty_when_nothingIsCloseEnough() throws IOException {
            note("Notizen/Gruppe.md", "---\ntitle: Gruppe\n---\n\nx\n");

            assertThat(index().resolve("Kategorientheorie", 0.88)).isEmpty();
        }

        // German compounds share long prefixes - "Schaden…" alone does not make two notes one.
        @Test
        @DisplayName("should not merge two compounds that merely start alike")
        void should_returnEmpty_when_onlyThePrefixMatches() throws IOException {
            note("Notizen/Schadennummer.md", "---\ntitle: Schadennummer\n---\n\nx\n");

            assertThat(index().resolve("Schadenmeldung", 0.88)).isEmpty();
        }

        @Test
        @DisplayName("should not merge notes about different dates or numbers")
        void should_returnEmpty_when_theNumbersDiffer() throws IOException {
            note("Notizen/Verkehrsunfall 2025.md", "---\ntitle: Verkehrsunfall 2025\n---\n\nx\n");

            assertThat(index().resolve("Verkehrsunfall 2026", 0.88)).isEmpty();
        }

        @Test
        @DisplayName("should treat typographic hyphens and spaces like plain ones")
        void should_resolve_when_onlyTheHyphenDiffers() throws IOException {
            Path file = note("Notizen/HWS-Distorsion.md", "---\ntitle: HWS-Distorsion\n---\n\nx\n");

            assertThat(index().resolve("HWS\u2011Distorsion", 0.88)).contains(file);
        }

        // A later run registering a draft under a variant spelling must not rename the note.
        @Test
        @DisplayName("should keep the title a note already has")
        void should_keepTheExistingTitle_when_registeredAgain() throws IOException {
            VaultIndex index = index();
            Path file = vault.resolve("Notizen/Gruppe.md");
            index.register("Gruppe", java.util.List.of(), file);

            index.register("Gruppen", java.util.List.of(), file);

            assertThat(index.titleOf(file)).isEqualTo("Gruppe");
        }

        @Test
        @DisplayName("should let a newly written note be found without a rebuild")
        void should_containNote_when_registeredAfterWriting() throws IOException {
            VaultIndex index = index();
            Path file = vault.resolve("Notizen/Neu.md");

            index.register("Neu", java.util.List.of("Neuling"), file);

            assertThat(index.resolve("Neuling", 0.88)).contains(file);
        }
    }
}
