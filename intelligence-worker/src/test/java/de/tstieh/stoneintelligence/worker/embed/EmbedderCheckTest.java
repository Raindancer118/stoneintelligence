package de.tstieh.stoneintelligence.worker.embed;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EmbedderCheckTest {

    @Test
    void should_reportOk_withTheRealModel() {
        var dir = LocalEmbedder.modelDir(System.getenv());
        assumeTrue(Files.exists(dir.resolve(LocalEmbedder.MODEL_FILE)), "Embedding-Modell fehlt unter " + dir);
        var original = System.out;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            EmbedderCheck.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8)).contains(LocalEmbedder.MODEL_ID).contains("OK");
    }
}
