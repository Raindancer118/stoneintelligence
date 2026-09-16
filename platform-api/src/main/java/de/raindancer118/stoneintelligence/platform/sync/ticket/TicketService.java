package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/**
 * Kurzlebige Single-Use-Tickets fuer WebSocket-Auth (Plan.md Abschnitt 3, positiv uebernommenes
 * Muster aus stonesync): der Obsidian-WS-Client kann keine Custom-Header senden, das Ticket wird
 * deshalb ueber die Query-Zeichenkette beim Handshake uebergeben.
 *
 * <p>Bewusst In-Memory (ConcurrentHashMap), keine DB-Tabelle: Tickets leben Sekunden, nicht
 * Tage - ein Neustart des Prozesses waehrend eines Handshakes ist ein akzeptabler Sonderfall.
 * Bei Mehr-Instanz-Betrieb (mehrere platform-api-Prozesse hinter einem Load Balancer) muss dieser
 * Store durch einen geteilten (z. B. Redis) ersetzt werden - das ist ein dokumentierter
 * Folgeschritt, kein Blocker fuer den vertikalen Sync-Slice.
 */
public class TicketService {

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, SyncTicket> store = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public TicketService(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    public SyncTicket issue(VaultId vaultId, NoteId noteId, String actor) {
        evictExpired();

        var now = clock.instant();
        var ticket = new SyncTicket(newToken(), vaultId, noteId, actor, now, now.plus(ttl));
        store.put(ticket.token(), ticket);
        return ticket;
    }

    public Optional<TicketClaims> redeem(String token) {
        var ticket = store.remove(token);
        if (ticket == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(ticket.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(new TicketClaims(ticket.vaultId(), ticket.noteId(), ticket.actor()));
    }

    private void evictExpired() {
        var now = clock.instant();
        store.values().removeIf(ticket -> now.isAfter(ticket.expiresAt()));
    }

    private String newToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
