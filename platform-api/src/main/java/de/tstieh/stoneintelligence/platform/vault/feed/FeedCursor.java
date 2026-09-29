package de.tstieh.stoneintelligence.platform.vault.feed;

import java.util.UUID;

/**
 * Wo ein Geraet im Aenderungs-Feed steht (ADR 0013). {@code since}: alle Transaktionen darunter
 * kennt es. Eine {@link Continuation} haelt eine laufende Seitenfolge fest: dieselbe Obergrenze
 * {@code upto} fuer alle Seiten, weiter nach {@code (lastTx, lastId)}.
 */
public record FeedCursor(long since, Continuation continuation) {

    public record Continuation(long upto, long lastTx, UUID lastId) {
    }

    public FeedCursor {
        if (since < 0) {
            throw new IllegalArgumentException("negative cursor");
        }
    }

    public static FeedCursor at(long since) {
        return new FeedCursor(since, null);
    }

    public static FeedCursor parse(String text) {
        try {
            var parts = text.split(":", -1);
            if (parts.length == 1) {
                return at(unsigned(parts[0]));
            }
            if (parts.length == 4) {
                return new FeedCursor(unsigned(parts[0]),
                    new Continuation(unsigned(parts[1]), unsigned(parts[2]), UUID.fromString(parts[3])));
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid cursor", invalid);
        }
        throw new IllegalArgumentException("invalid cursor");
    }

    public String format() {
        return continuation == null ? Long.toString(since)
            : since + ":" + continuation.upto() + ":" + continuation.lastTx() + ":" + continuation.lastId();
    }

    private static long unsigned(String digits) {
        if (!digits.matches("\\d{1,18}")) {
            throw new IllegalArgumentException("not a transaction id: " + digits);
        }
        return Long.parseLong(digits);
    }
}
