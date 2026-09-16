package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/** Ein ausgestelltes, noch nicht eingeloestes Sync-Ticket. */
public record SyncTicket(String token, VaultId vaultId, NoteId noteId, String actor,
                          Instant issuedAt, Instant expiresAt) {
}
