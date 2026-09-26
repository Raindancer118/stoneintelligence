package de.tstieh.stoneintelligence.stoneai.vault;

import de.tstieh.stoneintelligence.stoneai.config.ConfigSchema;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VaultInitializerTest {

    @TempDir
    Path root;

    private StoneAiConfig configFor(Path vault) {
        StoneAiConfig config = StoneAiConfig.defaults();
        ConfigSchema.byPath("vault.path").set(config, vault.toString());
        ConfigSchema.byPath("ingest.inbox").set(config, vault.resolve("Inbox").toString());
        return config;
    }

    @Nested
    @DisplayName("Creating a vault")
    class Creating {

        @Test
        @DisplayName("should create the folders Obsidian and StoneAI need")
        void should_createFolders_when_vaultIsNew() throws IOException {
            Path vault = root.resolve("StoneAI-Vault");

            VaultInitializer.initialise(configFor(vault));

            assertThat(vault.resolve(".obsidian/app.json")).exists();
            assertThat(vault.resolve("Notizen")).isDirectory();
            assertThat(vault.resolve("Quellen")).isDirectory();
            assertThat(vault.resolve("Anhänge")).isDirectory();
            assertThat(vault.resolve("_MOC")).isDirectory();
            assertThat(vault.resolve("Inbox")).isDirectory();
        }

        @Test
        @DisplayName("should leave a readme explaining how to keep notes away from the AI")
        void should_documentProtection_when_creatingVault() throws IOException {
            Path vault = root.resolve("v");

            VaultInitializer.initialise(configFor(vault));

            String readme = Files.readString(vault.resolve("README.md"));
            assertThat(readme).contains("NoStoneAI").contains(".stoneaiignore").contains("[noai]");
        }
    }

    @Nested
    @DisplayName("Running it again")
    class Idempotency {

        @Test
        @DisplayName("should not touch a file that already exists")
        void should_keepExistingFiles_when_runTwice() throws IOException {
            Path vault = root.resolve("v");
            VaultInitializer.initialise(configFor(vault));
            Path readme = vault.resolve("README.md");
            Files.writeString(readme, "Mein eigener Text.\n");

            VaultInitializer.initialise(configFor(vault));

            assertThat(Files.readString(readme)).isEqualTo("Mein eigener Text.\n");
        }

        @Test
        @DisplayName("should report which folders it actually created")
        void should_listCreatedPaths_when_initialising() throws IOException {
            Path vault = root.resolve("v");

            assertThat(VaultInitializer.initialise(configFor(vault))).isNotEmpty();
            assertThat(VaultInitializer.initialise(configFor(vault))).isEmpty();
        }
    }
}
