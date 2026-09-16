package de.raindancer118.stoneintelligence.platform.vault;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;

/**
 * Kodiert Epoch-ID + zuletzt gesehene {@link NoteId} (Keyset-Pagination statt Offset, damit
 * gleichzeitige Inserts keine Zeilen ueberspringen oder doppelt liefern) als opaken Cursor-Token.
 */
public final class ReconciliationCursor {

    private static final String NONE = "-";

    private final UUID epochId;
    private final Optional<NoteId> lastSeenId;

    private ReconciliationCursor(UUID epochId, Optional<NoteId> lastSeenId) {
        this.epochId = epochId;
        this.lastSeenId = lastSeenId;
    }

    public static ReconciliationCursor start(UUID epochId) {
        return new ReconciliationCursor(epochId, Optional.empty());
    }

    public static ReconciliationCursor of(UUID epochId, NoteId lastSeenId) {
        return new ReconciliationCursor(epochId, Optional.of(lastSeenId));
    }

    public static Optional<ReconciliationCursor> decode(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            var raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            var parts = raw.split("\\|", 2);
            var epochId = UUID.fromString(parts[0]);
            var lastSeenId = parts[1].equals(NONE) ? Optional.<NoteId>empty() : Optional.of(NoteId.of(parts[1]));
            return Optional.of(new ReconciliationCursor(epochId, lastSeenId));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed reconciliation cursor: " + token, e);
        }
    }

    public UUID epochId() {
        return epochId;
    }

    public Optional<NoteId> lastSeenId() {
        return lastSeenId;
    }

    public String encode() {
        var lastSeenPart = lastSeenId.map(id -> id.value().toString()).orElse(NONE);
        var raw = epochId + "|" + lastSeenPart;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
