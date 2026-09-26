package de.tstieh.stoneintelligence.platform.sync.ticket;

import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Ergebnis eines erfolgreichen {@link TicketService#redeem}. */
public record TicketClaims(VaultId vaultId, String actor) {
}
