package de.tstieh.stoneintelligence.worker.platform;

/** Eine Textstelle fuer "Frag den Vault"; {@code n} ist die Nummer, unter der die Antwort sie zitiert (ADR 0017). */
public record AskPassage(int n, String noteId, String path, String title, String heading, String text) {
}
