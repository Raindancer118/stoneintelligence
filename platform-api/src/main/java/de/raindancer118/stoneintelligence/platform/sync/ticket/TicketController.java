package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stellt kurzlebige Single-Use-Tickets fuer den WebSocket-Handshake aus (Plan.md Abschnitt 3).
 *
 * <p>{@code X-Actor} ersetzt provisorisch den authentifizierten Principal, bis OIDC in Phase 3
 * (Plan.md Abschnitt 6) verdrahtet ist - danach wird dieser Header durch das Auth-Prinzipal
 * ersetzt, nicht mehr vom Client frei behauptet.
 */
@RestController
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/sync-tickets")
    public IssuedTicketResponse issueTicket(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        @RequestHeader("X-Actor") String actor
    ) {
        var ticket = ticketService.issue(VaultId.of(vaultId), NoteId.of(noteId), actor);
        return new IssuedTicketResponse(ticket.token(), ticket.expiresAt());
    }

    public record IssuedTicketResponse(String token, Instant expiresAt) {
    }
}
