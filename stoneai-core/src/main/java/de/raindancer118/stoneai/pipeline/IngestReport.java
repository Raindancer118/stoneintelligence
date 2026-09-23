package de.raindancer118.stoneai.pipeline;

import de.raindancer118.stoneai.extract.ChunkFailure;
import de.raindancer118.stoneai.vault.WriteResult;

import java.nio.file.Path;
import java.util.List;

/**
 * What one document's run did. Skips and failures are first-class here, not log lines: the CLI
 * prints them, and a run that produced nothing has to say why.
 */
public record IngestReport(Path document,
                           String documentHash,
                           String title,
                           String skippedReason,
                           List<WriteResult> writes,
                           List<ChunkFailure> failures,
                           List<Integer> skippedPages,
                           int tokensUsed,
                           boolean budgetExhausted) {

    public IngestReport {
        writes = List.copyOf(writes);
        failures = List.copyOf(failures);
        skippedPages = List.copyOf(skippedPages);
    }

    public static IngestReport skipped(Path document, String reason) {
        return new IngestReport(document, "", document.getFileName().toString(), reason,
                List.of(), List.of(), List.of(), 0, false);
    }

    public boolean wasSkipped() {
        return skippedReason != null && !skippedReason.isEmpty();
    }

    public long notesWritten() {
        return writes.stream().filter(WriteResult::wrote).count();
    }

    public long notesProtected() {
        return writes.stream()
                .filter(write -> write.outcome() == WriteResult.Outcome.SKIPPED_PROTECTED)
                .count();
    }
}
