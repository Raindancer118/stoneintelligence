package de.raindancer118.stoneai.pipeline;

import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;
import de.raindancer118.stoneai.vault.InMemoryNoteStore;
import de.raindancer118.stoneai.vault.WriteResult;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pipeline writing into a vault it can only reach through a {@link de.raindancer118.stoneai.vault.NoteStore}
 * - how StoneIntelligence runs it: the uploaded document is a temporary file, the vault is shared.
 */
class HostedIngestTest {

    private static final Path VAULT = Path.of("/vault");
    private static final String ANSWER = """
            {"concepts":[
              {"title":"Äquivalenzrelation","aliases":["ÄR"],
               "definition":"Reflexiv, symmetrisch, transitiv.",
               "body":"Zerlegt eine Menge in Klassen.",
               "tags":["mathematik"],"entities":{},"related":["Relation"],"confidence":0.9}]}
            """;

    @TempDir
    Path upload;

    private StoneAiConfig config;
    private final InMemoryNoteStore store = new InMemoryNoteStore();

    @BeforeEach
    void setUp() {
        config = StoneAiConfig.defaults();
        ConfigSchema.byPath("vault.path").set(config, VAULT.toString());
    }

    private IngestReport ingest(String name, String content) throws IOException {
        return ingest(name, content, ProgressSink.NONE);
    }

    private IngestReport ingest(String name, String content, ProgressSink progress) throws IOException {
        Path document = upload.resolve(name);
        Files.writeString(document, content);
        LlmClient llm = new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                return new LlmAnswer(system.contains("Themenplan") ? IngestPipelineTest.PLAN : ANSWER, 10, "fake/model");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                return new LlmAnswer("", 0, "fake/vision");
            }
        };
        return IngestPipeline.hosted(config, llm, () -> LocalDate.of(2026, 9, 23)).withProgress(progress)
                .ingestInto(document, store);
    }

    @Test
    @DisplayName("should write notes, source note and index through the store only")
    void should_writeEverythingThroughTheStore() throws IOException {
        IngestReport report = ingest("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n");

        assertThat(report.notesWritten()).isEqualTo(1);
        assertThat(store.writes).containsExactly(VAULT.resolve("Notizen/Äquivalenzrelation.md"),
                VAULT.resolve("Quellen/Skript.md"), VAULT.resolve("_MOC/StoneAI-Index.md"));
        assertThat(Files.exists(VAULT)).isFalse();
    }

    // The upload is a temporary file - its location means nothing to anyone reading the vault.
    @Test
    @DisplayName("should name the original document without its temporary location")
    void should_notLeakTheTemporaryPath() throws IOException {
        ingest("Skript.md", "# Relationen\n\nText.\n");

        String sourceNote = store.notes.get(VAULT.resolve("Quellen/Skript.md"));
        assertThat(sourceNote).contains("Skript.md").doesNotContain(upload.toString());
    }

    // Undo lives in the plugin: the source note opens it with one click.
    @Test
    @DisplayName("should offer undoing the run right in the source note")
    void should_linkTheUndoDialog() throws IOException {
        ingest("Skript.md", "# Relationen\n\nText.\n");

        assertThat(store.notes.get(VAULT.resolve("Quellen/Skript.md"))).contains("(obsidian://stoneintelligence-ai-changes)");
    }

    // A long document needs a real in-between reading, not just "started" and "done".
    @Test
    @DisplayName("should report real progress between planning and writing, not just start and end")
    void should_reportProgress_throughTheStages() throws IOException {
        List<Integer> percents = new ArrayList<>();
        ingest("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n",
                (message, percent) -> percents.add(percent));

        assertThat(percents).isNotEmpty();
        assertThat(percents).isSorted();
        assertThat(percents.get(0)).isLessThan(50);
        assertThat(percents.get(percents.size() - 1)).isGreaterThan(percents.get(0));
    }

    @Test
    @DisplayName("should link to existing notes of the shared vault")
    void should_linkToExistingNotes() throws IOException {
        store.notes.put(VAULT.resolve("Mathe/Relation.md"), "# Relation\n\nVon Tom.\n");

        ingest("Skript.md", "# Relationen\n\nText.\n");

        assertThat(store.notes.get(VAULT.resolve("Notizen/Äquivalenzrelation.md"))).contains("[[Relation]]");
    }

    // A note a person wrote is never changed - the store says no, the run reports it and goes on.
    @Test
    @DisplayName("should leave a note alone that the store refuses, and say so")
    void should_reportRefusedNotes() throws IOException {
        Path human = VAULT.resolve("Mathe/Äquivalenzrelation.md");
        store.notes.put(human, "# Äquivalenzrelation\n\nTom hat das schon erklärt.\n");
        store.refused.add(human);

        IngestReport report = ingest("Skript.md", "# Relationen\n\nText.\n");

        assertThat(report.writes()).singleElement().satisfies(write -> {
            assertThat(write.outcome()).isEqualTo(WriteResult.Outcome.SKIPPED_PROTECTED);
            assertThat(write.reason()).contains("Menschen");
        });
        assertThat(store.notes.get(human)).isEqualTo("# Äquivalenzrelation\n\nTom hat das schon erklärt.\n");
    }
}
