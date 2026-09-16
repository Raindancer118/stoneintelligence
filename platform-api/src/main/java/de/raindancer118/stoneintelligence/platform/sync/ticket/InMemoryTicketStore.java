package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Einzelinstanz-Store (kein Mehr-Instanz-Betrieb) - vor allem fuer Tests und lokale Entwicklung. */
public final class InMemoryTicketStore implements TicketStore {

    private final Map<String, SyncTicket> store = new ConcurrentHashMap<>();

    @Override
    public void put(SyncTicket ticket) {
        store.put(ticket.token(), ticket);
    }

    @Override
    public Optional<SyncTicket> takeIfValid(String token, Instant now) {
        var ticket = store.remove(token);
        if (ticket == null || now.isAfter(ticket.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }
}
