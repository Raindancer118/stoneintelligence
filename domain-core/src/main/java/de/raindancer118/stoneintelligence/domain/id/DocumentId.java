package de.raindancer118.stoneintelligence.domain.id;

import java.util.Objects;
import java.util.UUID;

public record DocumentId(UUID value) implements EntityId {

    public DocumentId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static DocumentId newId() {
        return new DocumentId(UUID.randomUUID());
    }

    public static DocumentId of(UUID value) {
        return new DocumentId(value);
    }

    public static DocumentId of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            return new DocumentId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a valid DocumentId: " + value, e);
        }
    }

    @Override
    public String toString() {
        return "DocumentId(" + value + ")";
    }
}
