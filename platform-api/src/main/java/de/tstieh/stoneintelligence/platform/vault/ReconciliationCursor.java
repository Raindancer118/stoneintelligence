package de.tstieh.stoneintelligence.platform.vault;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Kodiert Epoch-ID + zuletzt gesehene Sequenznummer als opaken Cursor-Token (Keyset-Pagination,
 * Fehlerklasse 2). Bewusst die serverseitige {@code sequence}-Spalte (bigserial), NICHT die
 * NoteId: eine UUID hat keine Beziehung zur Einfuegereihenfolge - ein "id > letzte gesehene id"-
 * Cursor kann eine waehrend der Pagination neu eingefuegte Zeile mit "kleinerer" UUID dauerhaft
 * uebergehen, obwohl die letzte Seite faelschlich complete=true meldet. Eine monoton wachsende
 * Sequenznummer hat dieses Problem nicht.
 */
public final class ReconciliationCursor {

    private static final String NONE = "-";

    private final UUID epochId;
    private final Optional<Long> lastSeenSequence;

    private ReconciliationCursor(UUID epochId, Optional<Long> lastSeenSequence) {
        this.epochId = epochId;
        this.lastSeenSequence = lastSeenSequence;
    }

    public static ReconciliationCursor of(UUID epochId, long lastSeenSequence) {
        return new ReconciliationCursor(epochId, Optional.of(lastSeenSequence));
    }

    public static Optional<ReconciliationCursor> decode(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            var raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            var parts = raw.split("\\|", 2);
            var epochId = UUID.fromString(parts[0]);
            var lastSeenSequence = parts[1].equals(NONE) ? Optional.<Long>empty() : Optional.of(Long.parseLong(parts[1]));
            return Optional.of(new ReconciliationCursor(epochId, lastSeenSequence));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed reconciliation cursor: " + token, e);
        }
    }

    public UUID epochId() {
        return epochId;
    }

    public Optional<Long> lastSeenSequence() {
        return lastSeenSequence;
    }

    public String encode() {
        var lastSeenPart = lastSeenSequence.map(String::valueOf).orElse(NONE);
        var raw = epochId + "|" + lastSeenPart;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
