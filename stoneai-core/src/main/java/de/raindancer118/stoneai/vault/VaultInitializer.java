package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.config.StoneAiConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the default vault: the folders StoneAI writes into, a minimal Obsidian configuration
 * so the folder opens as a vault, and a readme that explains the protection mechanisms — the one
 * thing a new user needs to know before pointing an AI at their documents.
 *
 * <p>Idempotent by construction: an existing file is never rewritten, so running {@code init}
 * against a vault someone already uses adds what is missing and changes nothing else.
 */
public final class VaultInitializer {

    private VaultInitializer() {
    }

    /** @return the paths that were actually created; empty when everything was already there */
    public static List<Path> initialise(StoneAiConfig config) throws IOException {
        List<Path> created = new ArrayList<>();
        Path vault = config.vault().resolvedPath();

        for (Path directory : List.of(vault,
                vault.resolve(".obsidian"),
                config.vault().notesDir(),
                config.vault().sourcesDir(),
                config.vault().attachmentsDir(),
                config.vault().mocDir(),
                config.ingest().inboxDir())) {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
                created.add(directory);
            }
        }

        createIfAbsent(created, vault.resolve(".obsidian/app.json"), """
                {
                  "attachmentFolderPath": "%s",
                  "alwaysUpdateLinks": true,
                  "newLinkFormat": "shortest"
                }
                """.formatted(config.vault().attachmentsFolder()));

        createIfAbsent(created, vault.resolve("README.md"), readme(config));
        return created;
    }

    private static void createIfAbsent(List<Path> created, Path file, String content) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        created.add(file);
    }

    private static String readme(StoneAiConfig config) {
        return """
                # StoneAI-Vault

                Dieser Vault wird von **StoneAI** befüllt. Jede von der KI geschriebene Notiz trägt
                das Tag `#%s` — im Frontmatter und sichtbar im Text.

                ## Ordner

                | Ordner | Inhalt |
                |---|---|
                | `%s` | Die generierten Wissens-Notizen (ein Konzept = eine Notiz) |
                | `%s` | Je verarbeitetem Dokument eine Quellen-Notiz |
                | `%s` | Kopien der Originaldokumente |
                | `%s` | Index-Notizen |
                | `%s` | Ablage: was hier landet, wird von `stoneai watch` verarbeitet |

                ## Was StoneAI niemals anfasst

                Selbst geschriebener Text ist sicher: StoneAI schreibt ausschließlich zwischen seine
                eigenen Marker (`<!-- stoneai:begin … -->` / `<!-- stoneai:end … -->`) und kopiert
                alles andere unverändert durch. Vorhandene Frontmatter-Werte werden nie ersetzt.

                ## Ein Dokument ganz vor der KI schützen

                Vier Wege, jeder für sich ausreichend:

                1. **Tag:** `#%s` in die Notiz schreiben oder `%s` in die Frontmatter-`tags`.
                2. **Dateiname:** einen Marker einbauen, z. B. `Steuerbescheid [noai].pdf`.
                   Das gilt auch für Ordnernamen — alles darunter ist geschützt.
                3. **Ignore-Datei:** eine `%s` anlegen; Glob-Muster wie in `.gitignore`,
                   gültig ab ihrem Ordner abwärts (`!muster` nimmt wieder aus).
                4. **PDF-Metadaten:** `%s` in Keywords, Betreff oder Titel des PDFs eintragen.

                Geschützte Dokumente werden nicht gelesen, nicht an Groq oder Google gesendet und
                nicht verändert. `stoneai ingest --dry-run` zeigt vorher, was verarbeitet würde.
                """.formatted(config.notes().tag(),
                config.vault().notesFolder(), config.vault().sourcesFolder(),
                config.vault().attachmentsFolder(), config.vault().mocFolder(),
                config.ingest().inboxDir().getFileName(),
                config.protection().excludeTag(), config.protection().excludeTag(),
                config.protection().ignoreFileName(), config.protection().excludeTag());
    }
}
