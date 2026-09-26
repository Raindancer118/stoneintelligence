package de.raindancer118.stoneintelligence.platform.identity;

import java.util.Set;

/**
 * Was eine Person an einer Stelle darf und woher das kommt. {@code source == null} heisst: aus
 * ihren Vault-Rollen, keine Freigabe greift.
 */
public record EffectiveAccess(Set<Permission> permissions, AccessGrant source) {

    public EffectiveAccess {
        permissions = Set.copyOf(permissions);
    }

    public boolean allows(Permission permission) {
        return permissions.contains(permission);
    }

    public static EffectiveAccess none() {
        return new EffectiveAccess(Set.of(), null);
    }
}
