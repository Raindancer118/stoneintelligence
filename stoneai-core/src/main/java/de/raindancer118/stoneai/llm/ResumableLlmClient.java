package de.raindancer118.stoneai.llm;

import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Remembers every answer of one job on disk, so a job that has to start over asks only what it
 * has not asked before.
 *
 * <p>A long book is hundreds of calls. When the provider's daily quota runs out halfway, the job
 * waits and starts again from the beginning - and would spend the next day's quota on the same
 * first half, never reaching the end. With this client in front, a second attempt replays the
 * pages, sections and merges it already has and goes on from where the first one stopped.
 *
 * <p>Answers are keyed by the whole question (tier, system prompt, prompt, image), so a question
 * that differs in any way - a new topic plan, another vault state - is asked afresh; a replay can
 * never answer a question nobody asked. Only answers are kept, never failures. The directory holds
 * text derived from a user's document: the caller deletes it once the job is over
 * ({@link #delete}), and {@link #purgeOlderThan} removes what a job that never came back left.
 */
public final class ResumableLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final Path dir;
    private final AtomicInteger replayed = new AtomicInteger();

    public ResumableLlmClient(LlmClient delegate, Path dir) {
        this.delegate = delegate;
        this.dir = dir;
        try {
            Files.createDirectories(dir);
            // A job that is being worked on is not stale, even when every answer is a replay.
            Files.setLastModifiedTime(dir, FileTime.from(Instant.now()));
        } catch (IOException e) {
            throw new UncheckedIOException("Antwortspeicher " + dir + " nicht anlegbar", e);
        }
    }

    /** How many answers came from disk instead of the model. */
    public int replayed() {
        return replayed.get();
    }

    @Override
    public LlmAnswer complete(Tier tier, String system, String user) {
        String key = key("complete", tier.name().getBytes(StandardCharsets.UTF_8), bytes(system), bytes(user));
        return remembered(key, () -> delegate.complete(tier, system, user));
    }

    @Override
    public LlmAnswer readImage(byte[] pngImage, String prompt) {
        String key = key("image", pngImage, bytes(prompt));
        return remembered(key, () -> delegate.readImage(pngImage, prompt));
    }

    private LlmAnswer remembered(String key, java.util.function.Supplier<LlmAnswer> ask) {
        Path file = dir.resolve(key + ".answer");
        LlmAnswer known = read(file);
        if (known != null) {
            replayed.incrementAndGet();
            return known;
        }
        LlmAnswer answer = ask.get();
        write(file, answer);
        return answer;
    }

    /** Format: tokens, model, then the answer text - the text may span any number of lines. */
    private static LlmAnswer read(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException unreadable) {
            return null;
        }
        int first = content.indexOf('\n');
        int second = first < 0 ? -1 : content.indexOf('\n', first + 1);
        if (second < 0) {
            return null;
        }
        try {
            int tokens = Integer.parseInt(content.substring(0, first));
            return new LlmAnswer(content.substring(second + 1), tokens, content.substring(first + 1, second));
        } catch (NumberFormatException damaged) {
            return null;
        }
    }

    /** Written aside and moved in place, so a crash mid-write never leaves half an answer to replay. */
    private static void write(Path file, LlmAnswer answer) {
        String model = answer.model() == null ? "" : answer.model().replaceAll("[\\r\\n]", " ");
        String content = answer.tokensUsed() + "\n" + model + "\n" + (answer.text() == null ? "" : answer.text());
        try {
            Path temp = Files.createTempFile(file.getParent(), "answer-", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Not being able to remember costs a later attempt a call, not this one its answer.
        }
    }

    /** Removes a job's answers - once it is done, cancelled or failed for good. */
    public static void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // Whatever is left, purgeOlderThan removes later.
        }
    }

    /** Removes the answers of every job below {@code root} that has not been touched for {@code age}. */
    public static void purgeOlderThan(Path root, Duration age) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(age);
        try (Stream<Path> jobs = Files.list(root)) {
            jobs.filter(Files::isDirectory).filter(job -> lastTouched(job).isBefore(cutoff)).forEach(ResumableLlmClient::delete);
        } catch (IOException e) {
            // Tried again at the next job.
        }
    }

    private static Instant lastTouched(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toInstant();
        } catch (IOException e) {
            return Instant.MAX;
        }
    }

    private static byte[] bytes(String text) {
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
    }

    private static String key(String kind, byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(kind.getBytes(StandardCharsets.UTF_8));
            for (byte[] part : parts) {
                // Length first, so "ab"+"c" and "a"+"bc" can never share a key.
                digest.update(Integer.toString(part.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
