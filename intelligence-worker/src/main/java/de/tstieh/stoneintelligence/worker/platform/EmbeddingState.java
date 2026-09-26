package de.tstieh.stoneintelligence.worker.platform;

/** Womit und woraus die Vektoren einer Notiz berechnet wurden (ADR 0012). */
public record EmbeddingState(String noteId, String model, String contentHash) {
}
