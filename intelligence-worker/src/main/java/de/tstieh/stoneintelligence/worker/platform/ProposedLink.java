package de.tstieh.stoneintelligence.worker.platform;

/**
 * Ein vorgeschlagener Link (ADR 0012): Ziel-Notiz und das Wort, an dem er haengen soll; die Stelle
 * sucht der Server. {@code relation}: Art der Beziehung (Stufe 3), sonst {@code null}.
 */
public record ProposedLink(String target, String anchor, boolean allowRelated, String relation) {

    public ProposedLink(String target, String anchor, boolean allowRelated) {
        this(target, anchor, allowRelated, null);
    }
}
