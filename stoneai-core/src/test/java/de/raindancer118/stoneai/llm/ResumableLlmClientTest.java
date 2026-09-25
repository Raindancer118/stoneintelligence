package de.raindancer118.stoneai.llm;

import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmCapacityException;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Ein 800-Seiten-Buch, dem nach der Haelfte das Tageskontingent ausgeht, fing am naechsten Tag
// wieder bei Seite 1 an - und kam so nie ans Ende.
class ResumableLlmClientTest {

    @TempDir
    Path dir;

    private final AtomicInteger calls = new AtomicInteger();

    private final LlmClient model = new LlmClient() {
        @Override
        public LlmAnswer complete(Tier tier, String system, String user) {
            calls.incrementAndGet();
            return new LlmAnswer("Antwort auf " + user + "\nzweite Zeile", 1_234, "gemini/gemini-3.6-flash");
        }

        @Override
        public LlmAnswer readImage(byte[] pngImage, String prompt) {
            calls.incrementAndGet();
            return new LlmAnswer("Bildtext " + pngImage.length, 99, "groq/qwen");
        }
    };

    @Test
    @DisplayName("should answer a question it was asked before from disk, with the original tokens and model")
    void should_replayAKnownAnswer() {
        new ResumableLlmClient(model, dir).complete(Tier.FAST, "System", "Abschnitt 1");

        ResumableLlmClient resumed = new ResumableLlmClient(model, dir);
        LlmAnswer answer = resumed.complete(Tier.FAST, "System", "Abschnitt 1");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(answer.text()).isEqualTo("Antwort auf Abschnitt 1\nzweite Zeile");
        assertThat(answer.tokensUsed()).isEqualTo(1_234);
        assertThat(answer.model()).isEqualTo("gemini/gemini-3.6-flash");
        assertThat(resumed.replayed()).isEqualTo(1);
    }

    @Test
    @DisplayName("should ask the model again when anything about the question differs")
    void should_askAgain_whenTheQuestionDiffers() {
        ResumableLlmClient client = new ResumableLlmClient(model, dir);

        client.complete(Tier.FAST, "System", "Abschnitt 1");
        client.complete(Tier.SMART, "System", "Abschnitt 1");
        client.complete(Tier.FAST, "Anderes System", "Abschnitt 1");
        client.complete(Tier.FAST, "System", "Abschnitt 2");

        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("should remember image pages by their content")
    void should_replayImagePages() {
        new ResumableLlmClient(model, dir).readImage(new byte[]{1, 2, 3}, "Lies die Seite");

        ResumableLlmClient resumed = new ResumableLlmClient(model, dir);
        assertThat(resumed.readImage(new byte[]{1, 2, 3}, "Lies die Seite").text()).isEqualTo("Bildtext 3");
        resumed.readImage(new byte[]{1, 2, 4}, "Lies die Seite");

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("should remember nothing of a call that failed")
    void should_notRememberFailures() {
        LlmClient exhausted = new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                throw new LlmCapacityException("kein Kontingent frei", null);
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> new ResumableLlmClient(exhausted, dir).complete(Tier.FAST, "S", "U"))
                .isInstanceOf(LlmCapacityException.class);
        assertThat(new ResumableLlmClient(model, dir).complete(Tier.FAST, "S", "U").text()).startsWith("Antwort auf U");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("should cope with many calls at once")
    void should_beSafeForConcurrentCalls() {
        ResumableLlmClient client = new ResumableLlmClient(model, dir);

        List<String> answers = IntStream.range(0, 40).parallel()
                .mapToObj(i -> client.complete(Tier.FAST, "S", "Abschnitt " + (i % 10)).text()).toList();

        assertThat(answers).hasSize(40).allSatisfy(answer -> assertThat(answer).startsWith("Antwort auf Abschnitt "));
        assertThat(new ResumableLlmClient(model, dir).complete(Tier.FAST, "S", "Abschnitt 7").text())
                .isEqualTo("Antwort auf Abschnitt 7\nzweite Zeile");
    }

    @Test
    @DisplayName("should delete the answers of a job, and the answers of jobs untouched for too long")
    void should_deleteWhatIsNoLongerNeeded() throws Exception {
        Path finished = dir.resolve("job-a");
        Path stale = dir.resolve("job-b");
        Path waiting = dir.resolve("job-c");
        new ResumableLlmClient(model, finished).complete(Tier.FAST, "S", "U");
        new ResumableLlmClient(model, stale).complete(Tier.FAST, "S", "U");
        new ResumableLlmClient(model, waiting).complete(Tier.FAST, "S", "U");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(9))));

        ResumableLlmClient.delete(finished);
        ResumableLlmClient.purgeOlderThan(dir, Duration.ofDays(8));

        assertThat(finished).doesNotExist();
        assertThat(stale).doesNotExist();
        assertThat(waiting).isDirectory();
    }
}
