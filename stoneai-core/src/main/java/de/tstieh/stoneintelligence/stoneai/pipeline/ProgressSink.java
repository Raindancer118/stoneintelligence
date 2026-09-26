package de.tstieh.stoneintelligence.stoneai.pipeline;

/**
 * Reports how far a run has gotten, so a caller with its own status display (the intelligence
 * worker reporting to platform-api) does not have to guess between "started" and "done".
 */
public interface ProgressSink {

    ProgressSink NONE = (message, percent) -> {
    };

    void report(String message, int percent);

    /**
     * A sink that maps its own 0..100 onto {@code [from, to]} of this sink, so a sub-stage (one
     * chunk of many) can report its own progress without knowing where it sits in the whole run.
     */
    default ProgressSink scaled(int from, int to) {
        int span = to - from;
        return (message, percent) -> report(message, from + span * Math.max(0, Math.min(100, percent)) / 100);
    }
}
