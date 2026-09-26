package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Alle Aenderungen einer KI-Verarbeitung (z. B. ein eingelesenes Dokument) - wird als Ganzes
 * rueckgaengig gemacht. {@code service} ist die Id des {@link AiService}, der geschrieben hat,
 * {@code agent} seine Identitaet im Vault; {@code requestedBy} ist der Mensch, der die Verarbeitung ausgeloest hat.
 */
public record AiChangeSet(UUID id, VaultId vaultId, String service, String agent, String requestedBy, String label,
                          Instant createdAt, Instant revertedAt) {
}
