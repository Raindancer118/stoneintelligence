package de.tstieh.stoneintelligence.stoneai.vault;

import java.nio.file.Path;

/**
 * What happened to one note. Every outcome is explicit — especially the skips, because a run
 * that quietly does nothing is indistinguishable from one that worked.
 */
public record WriteResult(Outcome outcome, Path file, String reason, String preview) {

    public enum Outcome {
        /** The note did not exist and was written. */
        CREATED,
        /** The note existed; a managed block was added or updated, nothing else changed. */
        APPENDED,
        /** The note is protected from the AI and was not touched. */
        SKIPPED_PROTECTED,
        /** Nothing changed — the managed block already had exactly this content. */
        UNCHANGED
    }

    public static WriteResult created(Path file, String preview) {
        return new WriteResult(Outcome.CREATED, file, "", preview);
    }

    public static WriteResult appended(Path file, String preview) {
        return new WriteResult(Outcome.APPENDED, file, "", preview);
    }

    public static WriteResult unchanged(Path file) {
        return new WriteResult(Outcome.UNCHANGED, file, "", "");
    }

    public static WriteResult skipped(Path file, String reason) {
        return new WriteResult(Outcome.SKIPPED_PROTECTED, file, reason, "");
    }

    public boolean wrote() {
        return outcome == Outcome.CREATED || outcome == Outcome.APPENDED;
    }
}
