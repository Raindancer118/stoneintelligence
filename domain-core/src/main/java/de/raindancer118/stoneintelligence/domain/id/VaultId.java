package de.raindancer118.stoneintelligence.domain.id;

import java.util.Objects;
import java.util.UUID;

public record VaultId(UUID value) implements EntityId {

    public VaultId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static VaultId newId() {
        return new VaultId(UUID.randomUUID());
    }

    public static VaultId of(UUID value) {
        return new VaultId(value);
    }

    public static VaultId of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            return new VaultId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a valid VaultId: " + value, e);
        }
    }

    @Override
    public String toString() {
        return "VaultId(" + value + ")";
    }
}
