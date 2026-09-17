package de.raindancer118.stoneintelligence.platform.identity;

import java.util.Set;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/** Ein benanntes Berechtigungsbündel (Plan.md Abschnitt 4.4a) - Rollen sind frei definierbar, nicht fix. */
public record Role(UUID id, VaultId vaultId, String name, Set<Permission> permissions) {
}
