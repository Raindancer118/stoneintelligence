package de.tstieh.stoneintelligence.platform.identity;

import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Eine Mitgliederliste (Plan.md Abschnitt 4.4a). Rollen werden der Gruppe zugewiesen, nie
 * direkt dem Nutzer - Nutzer→Gruppe→Rolle ist die einzige Zuordnungskette.
 */
public record Group(UUID id, VaultId vaultId, String name, Set<String> memberSubjects) {
}
