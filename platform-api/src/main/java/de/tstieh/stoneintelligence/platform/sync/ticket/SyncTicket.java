package de.tstieh.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Ein ausgestelltes, noch nicht eingeloestes Sync-Ticket. Vault-skopiert, nicht mehr
 * notenskopiert - eine Verbindung joint/verlaesst beliebig viele Notiz-Raeume ueber denselben
 * Handshake (s. SyncWebSocketHandler), die Notiz-spezifische Berechtigungspruefung passiert erst
 * bei JOIN.
 */
public record SyncTicket(String token, VaultId vaultId, String actor, Instant issuedAt, Instant expiresAt) {
}
