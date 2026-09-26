package de.tstieh.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stellt kurzlebige Single-Use-Tickets fuer den WebSocket-Handshake aus (Plan.md Abschnitt 3).
 *
 * <p>Vault-skopiert (nicht mehr notenskopiert, s. ADR/Project.md Multiplexing-Umstellung): EINE
 * Verbindung joint/verlaesst darueber beliebig viele Notiz-Raeume, statt fuer jede Notiz eine
 * eigene Verbindung samt eigenem Ticket zu brauchen. Die notenspezifische
 * {@link Permission#READ}-Pruefung passiert deshalb erst bei JOIN (s. SyncWebSocketHandler),
 * nicht mehr hier - hier wird nur grundsaetzlicher Lesezugriff auf den Vault verlangt.
 *
 * <p>Der Actor kommt seit Phase 3 aus dem authentifizierten OIDC-Principal, nicht mehr aus einem
 * client-behaupteten Header (s. {@code SecurityConfig}). Bekannte Grenze (unveraendert): der
 * Relay unterscheidet einzelne WS-Nachrichten nicht nach Schreib-/Lese-Berechtigung - ein Join
 * mit READ gewaehrt effektiv volles Lesen+Schreiben ueber den Kanal.
 */
@RestController
public class TicketController {

    private final TicketService ticketService;
    private final VaultAccessGuard access;

    public TicketController(TicketService ticketService, VaultAccessGuard access) {
        this.ticketService = ticketService;
        this.access = access;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/sync-tickets")
    public IssuedTicketResponse issueTicket(@PathVariable String vaultId, Authentication authentication) {
        var actor = authentication.getName();
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, actor);

        var ticket = ticketService.issue(vId, actor);
        return new IssuedTicketResponse(ticket.token(), ticket.expiresAt());
    }

    public record IssuedTicketResponse(String token, Instant expiresAt) {
    }
}
