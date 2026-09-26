package de.tstieh.stoneintelligence.platform.ai;

/** Eine Notiz, die beim Rueckgaengigmachen bewusst nicht angefasst wurde - mit Grund. */
public record AiRevertConflict(String path, String reason) {
}
