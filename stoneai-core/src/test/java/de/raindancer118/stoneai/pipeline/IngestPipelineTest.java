package de.raindancer118.stoneai.pipeline;

import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;
import de.raindancer118.stoneai.ledger.ProcessingLedger;
import de.raindancer118.stoneai.vault.Frontmatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestPipelineTest {

    @TempDir
    Path root;

    private StoneAiConfig config;
    private Path vault;
    private Path inbox;
    private ProcessingLedger ledger;

    private static final String ANSWER = """
            {"concepts":[
              {"title":"Äquivalenzrelation","aliases":["ÄR"],
               "definition":"Reflexiv, symmetrisch, transitiv.",
               "body":"Zerlegt eine Menge in Klassen.",
               "tags":["mathematik"],"entities":{"begriff":["Reflexivität"]},
               "related":[],"confidence":0.9}]}
            """;


    /** The planner's answer: the one topic the fixed extraction answer is about. */
    static final String PLAN = """
            {"topics":[{"title":"Äquivalenzrelation","kind":"begriff","scope":"Definition","aliases":["ÄR"]}]}
            """;

    @BeforeEach
    void setUp() throws IOException {
        vault = root.resolve("vault");
        inbox = root.resolve("inbox");
        Files.createDirectories(vault);
        Files.createDirectories(inbox);
        config = StoneAiConfig.defaults();
        ConfigSchema.byPath("vault.path").set(config, vault.toString());
        ConfigSchema.byPath("ingest.inbox").set(config, inbox.toString());
        ConfigSchema.byPath("ingest.moveProcessed").set(config, "false");
        ledger = ProcessingLedger.load(root.resolve("ledger.json"));
    }

    private IngestPipeline pipeline(LlmClient llm) {
        return new IngestPipeline(config, llm, ledger, () -> LocalDate.of(2026, 9, 1), false);
    }

    private Path note(String name, String content) throws IOException {
        Path file = inbox.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Nested
    @DisplayName("A full run")
    class FullRun {

        @Test
        @DisplayName("should turn a document into notes, a source note and an index entry")
        void should_produceNotesAndProvenance_when_documentIsIngested() throws IOException {
            Path document = note("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n");

            IngestReport report = pipeline(new FixedLlm(ANSWER)).ingest(document);

            assertThat(report.notesWritten()).isEqualTo(1);
            assertThat(vault.resolve("Notizen/Äquivalenzrelation.md")).exists();
            assertThat(vault.resolve("Quellen/Skript.md")).exists();
            assertThat(vault.resolve("_MOC/StoneAI-Index.md")).exists();
        }

        @Test
        @DisplayName("should link every generated note back to its source note")
        void should_linkToSourceNote_when_writingNotes() throws IOException {
            Path document = note("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n");

            pipeline(new FixedLlm(ANSWER)).ingest(document);

            // Die Herkunft steht verlinkt in der Quellenangabe, nicht mehr in den Eigenschaften (Issue #1).
            assertThat(Files.readString(vault.resolve("Notizen/Äquivalenzrelation.md")))
                    .contains("*Quelle: [[Quellen/Skript|Skript — Relationen]]*");
        }

        @Test
        @DisplayName("should record the run in the ledger, including which provider saw the file")
        void should_recordInLedger_when_runCompletes() throws IOException {
            Path document = note("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n");

            IngestReport report = pipeline(new FixedLlm(ANSWER)).ingest(document);

            assertThat(ledger.isProcessed(report.documentHash())).isTrue();
            assertThat(ledger.find(report.documentHash()).orElseThrow().providers()).contains("fake");
        }
    }

    @Nested
    @DisplayName("Protection")
    class Protection {

        @Test
        @DisplayName("should refuse to read a protected document and call no model at all")
        void should_skipAndCallNoModel_when_documentIsProtected() throws IOException {
            Path document = note("Steuer [noai].md", "# Geheim\n\nSensibler Inhalt.\n");
            CountingLlm llm = new CountingLlm(ANSWER);

            IngestReport report = pipeline(llm).ingest(document);

            assertThat(report.skippedReason()).contains("noai");
            assertThat(report.notesWritten()).isZero();
            assertThat(llm.calls()).isZero();
        }

        @Test
        @DisplayName("should refuse a document protected by its tag")
        void should_skip_when_documentCarriesTheTag() throws IOException {
            Path document = note("Notiz.md", "---\ntags: [NoStoneAI]\n---\n\n# Geheim\n\nInhalt.\n");
            CountingLlm llm = new CountingLlm(ANSWER);

            assertThat(pipeline(llm).ingest(document).skippedReason()).isNotEmpty();
            assertThat(llm.calls()).isZero();
        }
    }

    @Nested
    @DisplayName("Repeated runs")
    class RepeatedRuns {

        @Test
        @DisplayName("should skip a document the ledger already knows")
        void should_skip_when_documentWasProcessedBefore() throws IOException {
            Path document = note("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n");
            pipeline(new FixedLlm(ANSWER)).ingest(document);
            CountingLlm llm = new CountingLlm(ANSWER);

            IngestReport second = pipeline(llm).ingest(document);

            assertThat(second.skippedReason()).containsIgnoringCase("bereits");
            assertThat(llm.calls()).isZero();
        }

        @Test
        @DisplayName("should process it again when asked to, leaving the vault unchanged")
        void should_beIdempotent_when_forced() throws IOException {
            Path document = note("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n");
            pipeline(new FixedLlm(ANSWER)).ingest(document);
            String before = Files.readString(vault.resolve("Notizen/Äquivalenzrelation.md"));

            pipeline(new FixedLlm(ANSWER)).force().ingest(document);

            assertThat(Files.readString(vault.resolve("Notizen/Äquivalenzrelation.md"))).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Reporting")
    class Reporting {

        @Test
        @DisplayName("should report an unreadable document instead of failing the whole run")
        void should_report_when_documentTypeIsUnsupported() throws IOException {
            Path document = note("bild.png", "kein Bild");

            IngestReport report = pipeline(new FixedLlm(ANSWER)).ingest(document);

            assertThat(report.skippedReason()).isNotEmpty();
            assertThat(report.notesWritten()).isZero();
        }

        @Test
        @DisplayName("should surface chunks the model could not answer for")
        void should_reportFailures_when_modelAnswersBadly() throws IOException {
            Path document = note("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n");

            IngestReport report = pipeline(new FixedLlm("kein JSON")).ingest(document);

            assertThat(report.failures()).isNotEmpty();
            assertThat(report.notesWritten()).isZero();
        }

        @Test
        @DisplayName("should write nothing at all on a dry run but still report what it would do")
        void should_writeNothing_when_dryRun() throws IOException {
            Path document = note("Skript.md", "# Relationen\n\nEine Relation ist eine Teilmenge.\n");

            IngestReport report = new IngestPipeline(config, new FixedLlm(ANSWER), ledger,
                    () -> LocalDate.of(2026, 9, 1), true).ingest(document);

            assertThat(report.notesWritten()).isEqualTo(1);
            assertThat(vault.resolve("Notizen")).doesNotExist();
            assertThat(ledger.size()).isZero();
        }
    }

    private static class FixedLlm implements LlmClient {

        private final String answer;

        FixedLlm(String answer) {
            this.answer = answer;
        }

        @Override
        public LlmAnswer complete(Tier tier, String system, String user) {
            return new LlmAnswer(system.contains("Themenplan") ? PLAN : answer, 50, "fake");
        }

        @Override
        public LlmAnswer readImage(byte[] pngImage, String prompt) {
            return new LlmAnswer("", 0, "fake");
        }
    }

    private static final class CountingLlm extends FixedLlm {

        private final List<String> calls = new ArrayList<>();

        CountingLlm(String answer) {
            super(answer);
        }

        int calls() {
            return calls.size();
        }

        @Override
        public LlmAnswer complete(Tier tier, String system, String user) {
            calls.add(user);
            return super.complete(tier, system, user);
        }
    }
}
