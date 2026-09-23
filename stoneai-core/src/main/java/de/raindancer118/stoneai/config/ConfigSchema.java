package de.raindancer118.stoneai.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalogue of every setting StoneAI has. This is the single source of truth: the YAML
 * loader, {@code stoneai config get/set/list} and the interactive UI all read the same list, so
 * a setting added here is editable everywhere without further wiring.
 */
public final class ConfigSchema {

    private static final List<ConfigOption> OPTIONS = List.of(
            ConfigOption.path("vault.path",
                    "Wurzelverzeichnis des Obsidian-Vaults, in den geschrieben wird",
                    config -> config.vault().path(),
                    (config, value) -> config.vault().path(value)),
            ConfigOption.text("vault.notesFolder",
                    "Unterordner für die generierten Wissens-Notizen",
                    config -> config.vault().notesFolder(),
                    (config, value) -> config.vault().notesFolder(value)),
            ConfigOption.text("vault.sourcesFolder",
                    "Unterordner für die Quellen-Notizen (eine je verarbeitetem Dokument)",
                    config -> config.vault().sourcesFolder(),
                    (config, value) -> config.vault().sourcesFolder(value)),
            ConfigOption.text("vault.attachmentsFolder",
                    "Unterordner für kopierte Originaldokumente",
                    config -> config.vault().attachmentsFolder(),
                    (config, value) -> config.vault().attachmentsFolder(value)),
            ConfigOption.text("vault.mocFolder",
                    "Unterordner für Index-/MOC-Notizen",
                    config -> config.vault().mocFolder(),
                    (config, value) -> config.vault().mocFolder(value)),
            ConfigOption.flag("vault.copyAttachments",
                    "Originaldokument in den Vault kopieren und verlinken",
                    config -> config.vault().copyAttachments(),
                    (config, value) -> config.vault().copyAttachments(value)),

            ConfigOption.path("ingest.inbox",
                    "Ordner, den der Watch-Modus überwacht",
                    config -> config.ingest().inbox(),
                    (config, value) -> config.ingest().inbox(value)),
            ConfigOption.text("ingest.processedFolder",
                    "Zielordner (relativ zum Vault) für bereits verarbeitete Dateien",
                    config -> config.ingest().processedFolder(),
                    (config, value) -> config.ingest().processedFolder(value)),
            ConfigOption.flag("ingest.moveProcessed",
                    "Verarbeitete Dateien nach ingest.processedFolder verschieben",
                    config -> config.ingest().moveProcessed(),
                    (config, value) -> config.ingest().moveProcessed(value)),
            ConfigOption.integer("ingest.maxPages",
                    "Obergrenze an Seiten je Dokument (schützt vor Kostenausreißern)", 1, 10_000,
                    config -> config.ingest().maxPages(),
                    (config, value) -> config.ingest().maxPages(value)),
            ConfigOption.integer("ingest.watchDebounceSeconds",
                    "Wartezeit, bis eine neue Datei im Inbox-Ordner als fertig gilt", 0, 600,
                    config -> config.ingest().watchDebounceSeconds(),
                    (config, value) -> config.ingest().watchDebounceSeconds(value)),

            ConfigOption.text("llm.language",
                    "Sprache, in der die Notizen geschrieben werden (ISO-Code, z. B. de)",
                    config -> config.llm().language(),
                    (config, value) -> config.llm().language(value)),
            ConfigOption.list("llm.fastChain",
                    "Fallback-Kette für die Massen-Extraktion, als provider:model",
                    config -> config.llm().fastChain(),
                    (config, value) -> config.llm().fastChain(value)),
            ConfigOption.list("llm.smartChain",
                    "Fallback-Kette für Konsolidierung und Notiztexte",
                    config -> config.llm().smartChain(),
                    (config, value) -> config.llm().smartChain(value)),
            ConfigOption.list("llm.visionChain",
                    "Fallback-Kette für OCR gescannter Seiten (braucht Vision-Modelle)",
                    config -> config.llm().visionChain(),
                    (config, value) -> config.llm().visionChain(value)),
            ConfigOption.decimal("llm.temperature",
                    "Sampling-Temperatur; niedrig hält die Extraktion nah am Dokument", 0.0, 2.0,
                    config -> config.llm().temperature(),
                    (config, value) -> config.llm().temperature(value)),
            ConfigOption.integer("llm.retryAttempts",
                    "Versuche je Aufruf, solange alle Anbieter nur vorübergehend ausfallen (überlastet, Rate-Limit)",
                    1, 10,
                    config -> config.llm().retryAttempts(),
                    (config, value) -> config.llm().retryAttempts(value)),
            ConfigOption.integer("llm.retryBackoffSeconds",
                    "Erste Wartezeit vor einem neuen Versuch; jede weitere ist dreimal so lang",
                    1, 600,
                    config -> config.llm().retryBackoffSeconds(),
                    (config, value) -> config.llm().retryBackoffSeconds(value)),
            ConfigOption.integer("llm.maxOutputTokens",
                    "Höchstlänge einer Antwort; zu knapp schneidet Antworten mitten im JSON ab",
                    512, 200_000,
                    config -> config.llm().maxOutputTokens(),
                    (config, value) -> config.llm().maxOutputTokens(value)),
            ConfigOption.integer("llm.maxTokensPerRun",
                    "Token-Budget je Lauf; ist es erschöpft, bricht der Lauf kontrolliert ab",
                    1_000, 100_000_000,
                    config -> config.llm().maxTokensPerRun(),
                    (config, value) -> config.llm().maxTokensPerRun(value)),
            ConfigOption.flag("llm.visionEnabled",
                    "Gescannte Seiten per Vision-Modell lesen (aus: solche Seiten werden übersprungen)",
                    config -> config.llm().visionEnabled(),
                    (config, value) -> config.llm().visionEnabled(value)),

            ConfigOption.text("notes.tag",
                    "Tag, das jede KI-geschriebene Notiz trägt",
                    config -> config.notes().tag(),
                    (config, value) -> config.notes().tag(value)),
            ConfigOption.list("notes.extraTags",
                    "Zusätzliche Tags für jede generierte Notiz",
                    config -> config.notes().extraTags(),
                    (config, value) -> config.notes().extraTags(value)),
            ConfigOption.decimal("notes.similarityThreshold",
                    "Ab welcher Titel-Ähnlichkeit eine Notiz als bereits vorhanden gilt", 0.0, 1.0,
                    config -> config.notes().similarityThreshold(),
                    (config, value) -> config.notes().similarityThreshold(value)),
            ConfigOption.integer("notes.maxNotesPerDocument",
                    "Obergrenze an Notizen, die ein einzelnes Dokument erzeugen darf", 1, 1_000,
                    config -> config.notes().maxNotesPerDocument(),
                    (config, value) -> config.notes().maxNotesPerDocument(value)),
            ConfigOption.flag("notes.writeSourceNote",
                    "Je Dokument eine Quellen-Notiz anlegen",
                    config -> config.notes().writeSourceNote(),
                    (config, value) -> config.notes().writeSourceNote(value)),
            ConfigOption.flag("notes.writeMoc",
                    "Index-/MOC-Notiz mit allen erzeugten Notizen pflegen",
                    config -> config.notes().writeMoc(),
                    (config, value) -> config.notes().writeMoc(value)),

            ConfigOption.text("protection.excludeTag",
                    "Tag, das ein Dokument oder eine Notiz vollständig vor der KI schützt",
                    config -> config.protection().excludeTag(),
                    (config, value) -> config.protection().excludeTag(value)),
            ConfigOption.text("protection.ignoreFileName",
                    "Dateiname der Ignore-Datei (Glob-Muster, gilt ab ihrem Ordner abwärts)",
                    config -> config.protection().ignoreFileName(),
                    (config, value) -> config.protection().ignoreFileName(value)),
            ConfigOption.list("protection.filenameMarkers",
                    "Marker im Dateinamen, die eine Datei schützen (z. B. [noai])",
                    config -> config.protection().filenameMarkers(),
                    (config, value) -> config.protection().filenameMarkers(value)),
            ConfigOption.list("protection.excludeGlobs",
                    "Immer ignorierte Pfadmuster, relativ zum Vault bzw. Startordner",
                    config -> config.protection().excludeGlobs(),
                    (config, value) -> config.protection().excludeGlobs(value)),
            ConfigOption.flag("protection.respectPdfKeywords",
                    "Schutz-Tag auch in den PDF-Metadaten (Keywords/Subject/Title) beachten",
                    config -> config.protection().respectPdfKeywords(),
                    (config, value) -> config.protection().respectPdfKeywords(value)),
            ConfigOption.flag("protection.protectExistingNotes",
                    "Geschützte Notizen im Vault niemals verändern, auch nicht anhängend",
                    config -> config.protection().protectExistingNotes(),
                    (config, value) -> config.protection().protectExistingNotes(value)));

    private static final Map<String, ConfigOption> BY_PATH = new LinkedHashMap<>();

    static {
        for (ConfigOption option : OPTIONS) {
            BY_PATH.put(option.path(), option);
        }
    }

    private ConfigSchema() {
    }

    public static List<ConfigOption> options() {
        return OPTIONS;
    }

    public static Set<String> paths() {
        return new LinkedHashSet<>(BY_PATH.keySet());
    }

    public static ConfigOption byPath(String path) {
        ConfigOption option = BY_PATH.get(path);
        if (option == null) {
            throw new ConfigException("unknown setting '" + path + "' — try 'stoneai config list'");
        }
        return option;
    }

    public static boolean has(String path) {
        return BY_PATH.containsKey(path);
    }

    /** Section names in the order they should be presented. */
    public static List<String> sections() {
        return OPTIONS.stream().map(ConfigOption::section).distinct().toList();
    }

    public static List<ConfigOption> sectionOptions(String section) {
        return OPTIONS.stream().filter(option -> option.section().equals(section)).toList();
    }
}
