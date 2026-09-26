package de.tstieh.stoneintelligence.stoneai.chunk;

/** A piece of a document small enough to hand to a model in one call, with its provenance. */
public record Chunk(int index, String text, Provenance provenance) {
}
