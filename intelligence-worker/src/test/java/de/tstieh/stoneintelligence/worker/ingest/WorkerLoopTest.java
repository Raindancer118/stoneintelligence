package de.tstieh.stoneintelligence.worker.ingest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerLoopTest {

    @TempDir
    Path resumeRoot;

    private final FakePlatform platform = new FakePlatform();

    private WorkerLoop loop() {
        LlmFactory llms = model -> null;
        var models = ServiceModels.from(java.util.Map.of());
        var processor = new JobProcessor(platform, llms, models, LocalDate::now, Duration.ZERO, resumeRoot);
        return new WorkerLoop(platform, processor, new CapacityReporter(platform, llms, models));
    }

    @Test
    void should_claimNoFurtherJob_afterShutdownBegan() {
        var job = new ClaimedJob(UUID.randomUUID(), "vault-1", "gemini", "tom", "a.md", "text/markdown", 1, 1, UUID.randomUUID(), 1);
        platform.queue.add(job);
        var loop = loop();

        loop.destroy();
        loop.drain();

        assertThat(platform.queue).containsExactly(job);
    }
}
