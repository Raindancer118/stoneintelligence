package de.raindancer118.stoneai.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything StoneAI can be configured with. Deliberately mutable and free of secrets: every
 * field is meant to be edited — from the CLI ({@code stoneai config set …}) or the interactive
 * UI — through {@link ConfigSchema}, which is the single place that knows the option paths,
 * their types and their documentation.
 *
 * <p>API keys never live here. They are read from the environment
 * ({@code GROQ_API_KEY[_n]}, {@code GOOGLE_API_KEY[_n]}) so a config file can be synced or
 * shared without leaking credentials.
 */
public final class StoneAiConfig {

    private final Vault vault = new Vault();
    private final Ingest ingest = new Ingest();
    private final Llm llm = new Llm();
    private final Notes notes = new Notes();
    private final Protection protection = new Protection();

    private StoneAiConfig() {
    }

    public static StoneAiConfig defaults() {
        return new StoneAiConfig();
    }

    public Vault vault() {
        return vault;
    }

    public Ingest ingest() {
        return ingest;
    }

    public Llm llm() {
        return llm;
    }

    public Notes notes() {
        return notes;
    }

    public Protection protection() {
        return protection;
    }

    /** Where the vault lives and how its folders are named. */
    public static final class Vault {

        private String path = "~/Dokumente/StoneAI-Vault";
        private String notesFolder = "Notizen";
        private String sourcesFolder = "Quellen";
        private String attachmentsFolder = "Anhänge";
        private String mocFolder = "_MOC";
        private boolean copyAttachments = true;

        public String path() {
            return path;
        }

        public void path(String value) {
            this.path = value;
        }

        public Path resolvedPath() {
            return UserPaths.expand(path);
        }

        public String notesFolder() {
            return notesFolder;
        }

        public void notesFolder(String value) {
            this.notesFolder = value;
        }

        public String sourcesFolder() {
            return sourcesFolder;
        }

        public void sourcesFolder(String value) {
            this.sourcesFolder = value;
        }

        public String attachmentsFolder() {
            return attachmentsFolder;
        }

        public void attachmentsFolder(String value) {
            this.attachmentsFolder = value;
        }

        public String mocFolder() {
            return mocFolder;
        }

        public void mocFolder(String value) {
            this.mocFolder = value;
        }

        public boolean copyAttachments() {
            return copyAttachments;
        }

        public void copyAttachments(boolean value) {
            this.copyAttachments = value;
        }

        public Path notesDir() {
            return resolvedPath().resolve(notesFolder);
        }

        public Path sourcesDir() {
            return resolvedPath().resolve(sourcesFolder);
        }

        public Path attachmentsDir() {
            return resolvedPath().resolve(attachmentsFolder);
        }

        public Path mocDir() {
            return resolvedPath().resolve(mocFolder);
        }
    }

    /** Which documents get picked up and how much of them is read. */
    public static final class Ingest {

        private String inbox = "~/Dokumente/StoneAI-Vault/Inbox";
        private String processedFolder = "Inbox/_erledigt";
        private boolean moveProcessed = true;
        private int maxPages = 1_000;
        private int watchDebounceSeconds = 3;

        public String inbox() {
            return inbox;
        }

        public void inbox(String value) {
            this.inbox = value;
        }

        public Path inboxDir() {
            return UserPaths.expand(inbox);
        }

        public String processedFolder() {
            return processedFolder;
        }

        public void processedFolder(String value) {
            this.processedFolder = value;
        }

        public boolean moveProcessed() {
            return moveProcessed;
        }

        public void moveProcessed(boolean value) {
            this.moveProcessed = value;
        }

        public int maxPages() {
            return maxPages;
        }

        public void maxPages(int value) {
            this.maxPages = value;
        }

        public int watchDebounceSeconds() {
            return watchDebounceSeconds;
        }

        public void watchDebounceSeconds(int value) {
            this.watchDebounceSeconds = value;
        }
    }

    /** Model routing. Entries are {@code provider:model}, tried left to right. */
    public static final class Llm {

        private String language = "de";
        // Model names age fast — a chain that names a retired model fails with a 404 that looks
        // like a broken install. Every entry here was verified against the live model list, and
        // each chain keeps a second provider behind the first so one outage is not a dead run.
        private final List<String> fastChain = new ArrayList<>(List.of(
                "groq:openai/gpt-oss-120b",
                "gemini:gemini-3.5-flash",
                "gemini:gemini-2.5-flash"));
        private final List<String> smartChain = new ArrayList<>(List.of(
                "gemini:gemini-2.5-pro",
                "gemini:gemini-3.5-flash",
                "groq:openai/gpt-oss-120b"));
        private final List<String> visionChain = new ArrayList<>(List.of(
                "gemini:gemini-2.5-flash",
                "gemini:gemini-3.5-flash",
                "gemini:gemini-2.5-pro"));
        private double temperature = 0.2;
        private int maxTokensPerRun = 400_000;
        private int maxTokensPerPage = 3_000;
        private int parallelCalls = 4;
        private int maxOutputTokens = 16_384;
        private int retryAttempts = 4;
        private int retryBackoffSeconds = 20;
        private boolean visionEnabled = true;

        public String language() {
            return language;
        }

        public void language(String value) {
            this.language = value;
        }

        public List<String> fastChain() {
            return List.copyOf(fastChain);
        }

        public void fastChain(List<String> value) {
            replace(fastChain, value);
        }

        public List<String> smartChain() {
            return List.copyOf(smartChain);
        }

        public void smartChain(List<String> value) {
            replace(smartChain, value);
        }

        public List<String> visionChain() {
            return List.copyOf(visionChain);
        }

        public void visionChain(List<String> value) {
            replace(visionChain, value);
        }

        public double temperature() {
            return temperature;
        }

        public void temperature(double value) {
            this.temperature = value;
        }

        public int retryAttempts() {
            return retryAttempts;
        }

        public void retryAttempts(int value) {
            this.retryAttempts = value;
        }

        public int retryBackoffSeconds() {
            return retryBackoffSeconds;
        }

        public void retryBackoffSeconds(int value) {
            this.retryBackoffSeconds = value;
        }

        public int maxOutputTokens() {
            return maxOutputTokens;
        }

        public void maxOutputTokens(int value) {
            this.maxOutputTokens = value;
        }

        public int maxTokensPerRun() {
            return maxTokensPerRun;
        }

        public void maxTokensPerRun(int value) {
            this.maxTokensPerRun = value;
        }

        public int maxTokensPerPage() {
            return maxTokensPerPage;
        }

        public void maxTokensPerPage(int value) {
            this.maxTokensPerPage = value;
        }

        /**
         * The budget for a document of {@code pages} pages: {@link #maxTokensPerRun()} at least,
         * {@link #maxTokensPerPage()} per page for a long one - a fixed budget read the first 170
         * pages of a textbook and left the rest.
         */
        public int tokenBudgetFor(int pages) {
            long scaled = (long) Math.max(0, pages) * Math.max(0, maxTokensPerPage);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(maxTokensPerRun, scaled));
        }

        public int parallelCalls() {
            return parallelCalls;
        }

        public void parallelCalls(int value) {
            this.parallelCalls = value;
        }

        public boolean visionEnabled() {
            return visionEnabled;
        }

        public void visionEnabled(boolean value) {
            this.visionEnabled = value;
        }

        private static void replace(List<String> target, List<String> value) {
            target.clear();
            target.addAll(value);
        }
    }

    /** How generated notes look. */
    public static final class Notes {

        private String tag = "StoneAI";
        private final List<String> extraTags = new ArrayList<>();
        private double similarityThreshold = 0.88;
        private int maxNotesPerDocument = 40;
        private boolean writeSourceNote = true;
        private boolean writeMoc = true;

        public String tag() {
            return tag;
        }

        public void tag(String value) {
            this.tag = value;
        }

        public List<String> extraTags() {
            return List.copyOf(extraTags);
        }

        public void extraTags(List<String> value) {
            extraTags.clear();
            extraTags.addAll(value);
        }

        public double similarityThreshold() {
            return similarityThreshold;
        }

        public void similarityThreshold(double value) {
            this.similarityThreshold = value;
        }

        public int maxNotesPerDocument() {
            return maxNotesPerDocument;
        }

        public void maxNotesPerDocument(int value) {
            this.maxNotesPerDocument = value;
        }

        public boolean writeSourceNote() {
            return writeSourceNote;
        }

        public void writeSourceNote(boolean value) {
            this.writeSourceNote = value;
        }

        public boolean writeMoc() {
            return writeMoc;
        }

        public void writeMoc(boolean value) {
            this.writeMoc = value;
        }
    }

    /**
     * Keeping documents away from the AI. Anything matched here is never read, never sent to a
     * provider and never modified — see {@code de.raindancer118.stoneai.protection}.
     */
    public static final class Protection {

        private String excludeTag = "NoStoneAI";
        private String ignoreFileName = ".stoneaiignore";
        private final List<String> filenameMarkers = new ArrayList<>(List.of("[noai]", "[privat]"));
        private final List<String> excludeGlobs = new ArrayList<>(List.of(".obsidian/**", ".git/**", ".trash/**"));
        private boolean respectPdfKeywords = true;
        private boolean protectExistingNotes = true;

        public String excludeTag() {
            return excludeTag;
        }

        public void excludeTag(String value) {
            this.excludeTag = value;
        }

        public String ignoreFileName() {
            return ignoreFileName;
        }

        public void ignoreFileName(String value) {
            this.ignoreFileName = value;
        }

        public List<String> filenameMarkers() {
            return List.copyOf(filenameMarkers);
        }

        public void filenameMarkers(List<String> value) {
            filenameMarkers.clear();
            filenameMarkers.addAll(value);
        }

        public List<String> excludeGlobs() {
            return List.copyOf(excludeGlobs);
        }

        public void excludeGlobs(List<String> value) {
            excludeGlobs.clear();
            excludeGlobs.addAll(value);
        }

        public boolean respectPdfKeywords() {
            return respectPdfKeywords;
        }

        public void respectPdfKeywords(boolean value) {
            this.respectPdfKeywords = value;
        }

        public boolean protectExistingNotes() {
            return protectExistingNotes;
        }

        public void protectExistingNotes(boolean value) {
            this.protectExistingNotes = value;
        }
    }
}
