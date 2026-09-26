package de.tstieh.stoneintelligence.worker.platform;

/** Ein Abschnitt einer Notiz mit seinem (normierten) Vektor. */
public record EmbeddedChunk(int index, String heading, float[] vector) {
}
