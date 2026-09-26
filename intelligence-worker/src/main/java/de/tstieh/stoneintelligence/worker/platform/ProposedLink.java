package de.tstieh.stoneintelligence.worker.platform;

/** Ein vorgeschlagener Link (ADR 0012): Ziel-Notiz und das Wort, an dem er haengen soll. Die Stelle sucht der Server. */
public record ProposedLink(String target, String anchor, boolean allowRelated) {
}
