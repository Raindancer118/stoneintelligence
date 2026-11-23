package de.tstieh.stoneintelligence.worker.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class StaleWorkDirsTest {

    @TempDir
    Path tmp;

    private Path workDir(String name, Duration age) throws Exception {
        var dir = Files.createDirectory(tmp.resolve(name));
        Files.writeString(dir.resolve("Vertrag.pdf"), "Inhalt");
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(age)));
        return dir;
    }

    @Test
    void should_removeOldJobDirectories_leftBehindByAKilledWorker() throws Exception {
        var old = workDir("stoneai-job-123", Duration.ofHours(3));
        var running = workDir("stoneai-job-456", Duration.ofMinutes(5));
        var foreign = workDir("something-else", Duration.ofDays(3));

        var removed = StaleWorkDirs.sweep(tmp, Duration.ofHours(1));

        assertThat(removed).isEqualTo(1);
        assertThat(old).doesNotExist();
        assertThat(running).exists();
        assertThat(foreign).exists();
    }

    @Test
    void should_doNothing_whenTheDirectoryIsMissing() {
        assertThat(StaleWorkDirs.sweep(tmp.resolve("fehlt"), Duration.ofHours(1))).isZero();
    }
}
