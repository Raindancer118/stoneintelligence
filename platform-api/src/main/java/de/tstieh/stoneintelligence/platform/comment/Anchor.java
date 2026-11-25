package de.tstieh.stoneintelligence.platform.comment;

/**
 * Die kommentierte Textstelle: das Zitat und etwas Umgebung davor und danach. Bewusst keine
 * Zeichenpositionen - die verrutschen, sobald jemand davor tippt; der Client sucht das Zitat im
 * aktuellen Text und zeigt "Stelle geändert", wenn es fehlt.
 */
public record Anchor(String quote, String prefix, String suffix) {

    static final int MAX_QUOTE = 1000;
    static final int MAX_CONTEXT = 200;

    public Anchor {
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? "" : suffix;
    }

    boolean valid() {
        return quote != null && !quote.isBlank() && quote.length() <= MAX_QUOTE
            && prefix.length() <= MAX_CONTEXT && suffix.length() <= MAX_CONTEXT;
    }
}
