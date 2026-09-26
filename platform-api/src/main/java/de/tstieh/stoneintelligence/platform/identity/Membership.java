package de.tstieh.stoneintelligence.platform.identity;

import java.util.Set;
import java.util.UUID;

/**
 * Wer jemand in einem Vault ist: seine Gruppen und die Rechte aus deren Rollen. Ohne Gruppe ist
 * er kein Mitglied - dann greift auch keine Freigabe (ADR 0011: nur Mitglieder).
 */
public record Membership(String subject, Set<UUID> groupIds, Set<Permission> vaultPermissions) {

    public Membership {
        groupIds = Set.copyOf(groupIds);
        vaultPermissions = Set.copyOf(vaultPermissions);
    }

    public boolean isMember() {
        return !groupIds.isEmpty();
    }
}
