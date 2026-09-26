package de.tstieh.stoneintelligence.worker.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Ein Probelauf schreibt in einen lokalen Ordner statt in den gemeinsamen Vault - zum Pruefen der Qualitaet. */
class TrialRunTest {

    @TempDir
    Path dir;

    @Test
    void should_writeTheNotesOfSeveralDocuments_intoOneLocalVault() throws Exception {
        var first = Files.writeString(dir.resolve("Brief.md"), "# Brief\n\nUnfall am 5. Juni.\n");
        var second = Files.writeString(dir.resolve("Schilderung.md"), "# Schilderung\n\nDer Unfall.\n");
        var vault = dir.resolve("vault");
        LlmClient llm = new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                return new LlmAnswer(system.contains("Themenplan")
                    ? "{\"topics\":[{\"title\":\"Verkehrsunfall\",\"kind\":\"ereignis\",\"scope\":\"alles\"}]}"
                    : "{\"concepts\":[{\"title\":\"Verkehrsunfall\",\"body\":\"Inhalt aus " + (user.contains("Brief") ? "Brief" : "Schilderung") + ".\"}]}",
                    1, "fake");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                return new LlmAnswer("", 0, "fake");
            }
        };

        var reports = TrialRun.run(List.of(first, second), vault, ServiceModels.from(java.util.Map.of()).forService("probe"), llm);

        assertThat(reports).hasSize(2);
        var note = Files.readString(vault.resolve("Notizen/Verkehrsunfall.md"));
        assertThat(note).contains("Inhalt aus Brief").contains("Inhalt aus Schilderung");
    }
}
