package de.raindancer118.stoneai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigTest {

    @Nested
    @DisplayName("The option schema")
    class Schema {

        @Test
        @DisplayName("should expose every option with a unique dotted path and a description")
        void should_describeEveryOption_when_schemaIsListed() {
            List<ConfigOption> options = ConfigSchema.options();

            assertThat(options).isNotEmpty();
            assertThat(options).extracting(ConfigOption::path).doesNotHaveDuplicates();
            assertThat(options).allSatisfy(option -> {
                assertThat(option.path()).matches("[a-z]+\\.[a-zA-Z]+");
                assertThat(option.description()).isNotBlank();
                assertThat(option.section()).isNotBlank();
            });
        }

        @Test
        @DisplayName("should cover the settings a user is most likely to change")
        void should_containCoreOptions_when_schemaIsListed() {
            assertThat(ConfigSchema.paths()).contains(
                    "vault.path", "vault.notesFolder", "ingest.inbox",
                    "llm.fastChain", "llm.smartChain", "notes.tag",
                    "protection.excludeTag", "protection.filenameMarkers");
        }

        // Ein festes Budget von 400.000 Token reichte fuer etwa 170 dichte Seiten - der Rest eines
        // Lehrbuchs blieb ungelesen. Das Budget waechst mit dem Dokument, das Minimum bleibt.
        @Test
        @DisplayName("should grow the token budget with the pages of the document, never below the base")
        void should_scaleTheTokenBudget_withThePages() {
            StoneAiConfig config = StoneAiConfig.defaults();
            config.llm().maxTokensPerRun(400_000);
            config.llm().maxTokensPerPage(3_000);

            assertThat(config.llm().tokenBudgetFor(10)).isEqualTo(400_000);
            assertThat(config.llm().tokenBudgetFor(500)).isEqualTo(1_500_000);

            config.llm().maxTokensPerPage(0);
            assertThat(config.llm().tokenBudgetFor(500)).isEqualTo(400_000);
        }

        @Test
        @DisplayName("should offer parallel calls, the page budget and room for long books")
        void should_offerTheLongDocumentOptions() {
            StoneAiConfig config = StoneAiConfig.defaults();

            assertThat(ConfigSchema.paths()).contains("llm.parallelCalls", "llm.maxTokensPerPage");
            assertThat(config.llm().parallelCalls()).isEqualTo(4);
            assertThat(config.ingest().maxPages()).isEqualTo(1_000);
            assertThatThrownBy(() -> ConfigSchema.byPath("llm.parallelCalls").set(config, "0"))
                    .isInstanceOf(ConfigException.class);
        }

        @Test
        @DisplayName("should read and write every option through the schema without special cases")
        void should_roundTripEveryOption_when_setThroughSchema() {
            StoneAiConfig config = StoneAiConfig.defaults();

            for (ConfigOption option : ConfigSchema.options()) {
                String rendered = option.render(config);
                assertThat(rendered).as("rendered value of %s", option.path()).isNotNull();
                option.set(config, rendered);
                assertThat(option.render(config)).as("round trip of %s", option.path()).isEqualTo(rendered);
            }
        }

        @Test
        @DisplayName("should reject values that do not fit the option type")
        void should_throw_when_valueDoesNotFitType() {
            StoneAiConfig config = StoneAiConfig.defaults();

            assertThatThrownBy(() -> ConfigSchema.byPath("ingest.maxPages").set(config, "many"))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("ingest.maxPages");
            assertThatThrownBy(() -> ConfigSchema.byPath("vault.copyAttachments").set(config, "maybe"))
                    .isInstanceOf(ConfigException.class);
            assertThatThrownBy(() -> ConfigSchema.byPath("notes.similarityThreshold").set(config, "2.5"))
                    .isInstanceOf(ConfigException.class);
        }

        @Test
        @DisplayName("should fail on an unknown option path instead of silently ignoring it")
        void should_throw_when_pathIsUnknown() {
            assertThatThrownBy(() -> ConfigSchema.byPath("vault.nonsense"))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("vault.nonsense");
        }

        @Test
        @DisplayName("should parse list options from a comma separated string")
        void should_parseList_when_optionIsAList() {
            StoneAiConfig config = StoneAiConfig.defaults();

            ConfigSchema.byPath("notes.extraTags").set(config, "uni, mathe , ");

            assertThat(config.notes().extraTags()).containsExactly("uni", "mathe");
        }
    }

    @Nested
    @DisplayName("Loading and saving")
    class LoadingAndSaving {

        @Test
        @DisplayName("should write a commented file that reads back to an identical config")
        void should_roundTripThroughYaml_when_savedAndLoaded(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("config.yaml");
            StoneAiConfig original = StoneAiConfig.defaults();
            ConfigSchema.byPath("vault.path").set(original, "/tmp/some-vault");
            ConfigSchema.byPath("notes.extraTags").set(original, "uni,mathe");

            ConfigStore.save(file, original);
            StoneAiConfig loaded = ConfigStore.load(file);

            assertThat(Files.readString(file)).startsWith("#");
            for (ConfigOption option : ConfigSchema.options()) {
                assertThat(option.render(loaded)).as(option.path()).isEqualTo(option.render(original));
            }
        }

        @Test
        @DisplayName("should fall back to the defaults when no config file exists yet")
        void should_returnDefaults_when_fileIsMissing(@TempDir Path dir) throws Exception {
            StoneAiConfig loaded = ConfigStore.load(dir.resolve("absent.yaml"));

            assertThat(loaded.notes().tag()).isEqualTo(StoneAiConfig.defaults().notes().tag());
        }

        @Test
        @DisplayName("should keep defaults for keys the file does not mention")
        void should_mergeOntoDefaults_when_fileIsPartial(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("config.yaml");
            Files.writeString(file, "notes:\n  tag: EigenesTag\n");

            StoneAiConfig loaded = ConfigStore.load(file);

            assertThat(loaded.notes().tag()).isEqualTo("EigenesTag");
            assertThat(loaded.vault().notesFolder()).isEqualTo(StoneAiConfig.defaults().vault().notesFolder());
        }

        @Test
        @DisplayName("should reject an unknown key rather than silently dropping the user's setting")
        void should_throw_when_fileContainsUnknownKey(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("config.yaml");
            Files.writeString(file, "notes:\n  tagg: Tippfehler\n");

            assertThatThrownBy(() -> ConfigStore.load(file))
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("notes.tagg");
        }

        @Test
        @DisplayName("should never write the config non-atomically, leaving no partial file behind")
        void should_writeAtomically_when_saving(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("nested/deeper/config.yaml");

            ConfigStore.save(file, StoneAiConfig.defaults());

            assertThat(file).exists();
            try (var entries = Files.list(file.getParent())) {
                assertThat(entries).containsExactly(file);
            }
        }
    }

    @Nested
    @DisplayName("Path handling")
    class PathHandling {

        @Test
        @DisplayName("should expand a leading tilde against the user's home directory")
        void should_expandTilde_when_pathStartsWithTilde() {
            StoneAiConfig config = StoneAiConfig.defaults();
            ConfigSchema.byPath("vault.path").set(config, "~/Dokumente/Test-Vault");

            assertThat(config.vault().resolvedPath())
                    .isEqualTo(Path.of(System.getProperty("user.home"), "Dokumente", "Test-Vault"));
        }

        @Test
        @DisplayName("should resolve vault sub folders below the vault root")
        void should_resolveFoldersBelowVault_when_asked() {
            StoneAiConfig config = StoneAiConfig.defaults();
            ConfigSchema.byPath("vault.path").set(config, "/tmp/v");

            assertThat(config.vault().notesDir()).isEqualTo(Path.of("/tmp/v", config.vault().notesFolder()));
            assertThat(config.vault().sourcesDir()).isEqualTo(Path.of("/tmp/v", config.vault().sourcesFolder()));
        }
    }
}
