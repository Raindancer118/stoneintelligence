package de.raindancer118.stoneintelligence.platform.sync.ticket;

import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/** Ergebnis eines erfolgreichen {@link TicketService#redeem}. */
public record TicketClaims(VaultId vaultId, NoteId noteId, String actor) {
}
