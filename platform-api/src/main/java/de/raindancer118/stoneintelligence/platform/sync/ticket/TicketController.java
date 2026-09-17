package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.vault.NoteNotFoundException;
import de.raindancer118.stoneintelligence.platform.vault.NoteRepository;
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
    private final NoteRepository notes;

    public TicketController(TicketService ticketService, NoteRepository notes) {
        this.ticketService = ticketService;
        this.notes = notes;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/sync-tickets")
    public IssuedTicketResponse issueTicket(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        @RequestHeader("X-Actor") String actor
    ) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        // Ohne diese Pruefung koennte jeder, der eine fremde NoteId kennt/eraet, ueber einen
        // beliebigen vaultId-Pfad ein gueltiges Sync-Ticket fuer sie bekommen und ihre volle
        // Yjs-Historie lesen/schreiben - Relay und SnapshotStore adressieren danach nur noch
        // ueber die NoteId, der Vault-Claim aus dem Ticket ist die einzige Durchsetzungsstelle.
        notes.findById(vId, nId).orElseThrow(() -> new NoteNotFoundException(vId, nId));

        var ticket = ticketService.issue(vId, nId, actor);
        return new IssuedTicketResponse(ticket.token(), ticket.expiresAt());
    }

    public record IssuedTicketResponse(String token, Instant expiresAt) {
    }
}
