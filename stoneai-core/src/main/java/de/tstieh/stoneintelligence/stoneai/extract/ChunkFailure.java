package de.tstieh.stoneintelligence.stoneai.extract;

/**
 * A chunk the model could not be made to answer about properly. Reported rather than swallowed:
 * a run that quietly drops a third of a document looks exactly like a run that worked.
 */
public record ChunkFailure(int chunkIndex, String provenanceLabel, String reason) {
}
