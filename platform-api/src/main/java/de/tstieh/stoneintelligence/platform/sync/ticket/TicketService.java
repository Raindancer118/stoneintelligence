package de.tstieh.stoneintelligence.platform.sync.ticket;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Kurzlebige Single-Use-Tickets fuer WebSocket-Auth (Plan.md Abschnitt 3, positiv uebernommenes
 * Muster aus stonesync): der Obsidian-WS-Client kann keine Custom-Header senden, das Ticket wird
 * deshalb ueber die Query-Zeichenkette beim Handshake uebergeben.
 *
 * <p>Persistenz ist ueber {@link TicketStore} ausgelagert: Produktion nutzt
 * {@link JdbcTicketStore} (Mehr-Instanz-faehig, mehrere platform-api-Prozesse teilen sich den
 * Store), Tests koennen {@link InMemoryTicketStore} verwenden.
 */
public class TicketService {

    private final Clock clock;
    private final Duration ttl;
    private final TicketStore store;
    private final SecureRandom random = new SecureRandom();

    public TicketService(Clock clock, Duration ttl, TicketStore store) {
        this.clock = clock;
        this.ttl = ttl;
        this.store = store;
    }

    public SyncTicket issue(VaultId vaultId, String actor) {
        return issue(vaultId, actor, null);
    }

    /** {@code deviceId}: die meldende Plugin-Instanz (ADR 0019); der Relay prueft sie beim Verbinden erneut. */
    public SyncTicket issue(VaultId vaultId, String actor, String deviceId) {
        var now = clock.instant();
        var ticket = new SyncTicket(newToken(), vaultId, actor, now, now.plus(ttl), deviceId);
        store.put(ticket);
        return ticket;
    }

    public Optional<TicketClaims> redeem(String token) {
        return store.takeIfValid(token, clock.instant())
            .map(ticket -> new TicketClaims(ticket.vaultId(), ticket.actor(), ticket.deviceId()));
    }

    private String newToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
