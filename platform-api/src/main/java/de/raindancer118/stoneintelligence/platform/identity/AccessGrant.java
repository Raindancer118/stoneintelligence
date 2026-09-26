package de.raindancer118.stoneintelligence.platform.identity;

import java.util.Set;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/**
 * Rechte je Ordner oder Eintrag (ADR 0011). {@code permissions == null} heisst "wie im Vault":
 * die Freigabe hebt eine weiter oben liegende Einschraenkung wieder auf. Eine leere Menge heisst
 * "gar nichts", der Eintrag ist dann fuer diese Person unsichtbar.
 */
public record AccessGrant(UUID id, VaultId vaultId, GrantTarget target, GrantScope scope, Set<Permission> permissions) {

    public AccessGrant {
        permissions = permissions == null ? null : Set.copyOf(permissions);
    }

    public boolean inheritsVault() {
        return permissions == null;
    }
}
