package de.tstieh.stoneintelligence.domain.id;

import java.util.Objects;
import java.util.UUID;

public record NoteId(UUID value) implements EntityId {

    public NoteId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static NoteId newId() {
        return new NoteId(UUID.randomUUID());
    }

    public static NoteId of(UUID value) {
        return new NoteId(value);
    }

    public static NoteId of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            return new NoteId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a valid NoteId: " + value, e);
        }
    }

    @Override
    public String toString() {
        return "NoteId(" + value + ")";
    }
}
