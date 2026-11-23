package de.tstieh.stoneintelligence.worker.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ein beendeter Prozess (OOM, kill -9) raeumt seine {@code stoneai-job-*}-Ordner nicht mehr auf -
 * darin liegen hochgeladene Dokumente, oft mit personenbezogenen Daten. Beim Start wird geloescht,
 * was aelter ist als jeder noch laufende Job sein kann.
 */
final class StaleWorkDirs {

    static final String PREFIX = "stoneai-job-";
    private static final Logger LOG = LoggerFactory.getLogger(StaleWorkDirs.class);

    private StaleWorkDirs() {
    }

    static int sweep(Path tmp, Duration olderThan) {
        if (!Files.isDirectory(tmp)) {
            return 0;
        }
        var cutoff = Instant.now().minus(olderThan);
        var removed = 0;
        try (var entries = Files.list(tmp)) {
            for (var dir : entries.filter(path -> path.getFileName().toString().startsWith(PREFIX)).toList()) {
                if (Files.isDirectory(dir) && Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) {
                    deleteTree(dir);
                    removed++;
                }
            }
        } catch (IOException e) {
            LOG.warn("Alte Arbeitsordner in {} nicht aufgeraeumt", tmp, e);
        }
        if (removed > 0) {
            LOG.info("{} verwaiste Arbeitsordner geloescht", removed);
        }
        return removed;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
