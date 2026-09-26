package de.tstieh.stoneintelligence.worker.platform;

/** Eine aehnliche Notiz: ihr passendster Abschnitt und die Kosinus-Aehnlichkeit (1 = gleich). */
public record SimilarChunk(String noteId, int chunk, String heading, double similarity) {
}
