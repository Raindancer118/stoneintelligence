package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistenz-Port fuer {@link SyncTicket}s. Getrennt von {@link TicketService}, damit
 * Mehr-Instanz-Betrieb (mehrere platform-api-Prozesse hinter einem Load Balancer) einen
 * geteilten Store (Postgres, {@link JdbcTicketStore}) statt eines prozesslokalen
 * {@link InMemoryTicketStore} nutzen kann, ohne die Ausstellungs-/Einloese-Logik zu aendern.
 */
public interface TicketStore {

    void put(SyncTicket ticket);

    /**
     * Atomar: liefert das Ticket nur, wenn es existiert UND noch nicht abgelaufen ist, und
     * entfernt es in jedem Fall (Single-Use) - verhindert einen Race zwischen zwei
     * gleichzeitigen Einloeseversuchen desselben Tokens.
     */
    Optional<SyncTicket> takeIfValid(String token, Instant now);
}
