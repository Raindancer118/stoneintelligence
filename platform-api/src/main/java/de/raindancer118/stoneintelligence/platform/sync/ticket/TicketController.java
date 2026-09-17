package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.vault.NoteNotFoundException;
import de.raindancer118.stoneintelligence.platform.vault.NoteRepository;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stellt kurzlebige Single-Use-Tickets fuer den WebSocket-Handshake aus (Plan.md Abschnitt 3).
 *
 * <p>Der Actor kommt seit Phase 3 aus dem authentifizierten OIDC-Principal, nicht mehr aus einem
 * client-behaupteten Header (s. {@code SecurityConfig}). Eine Ticket-Ausstellung setzt
 * mindestens {@link Permission#READ} auf die Note voraus - eine feinere Aufteilung
 * (Nur-Lese-Ticket vs. Schreib-Ticket) ist NICHT umgesetzt: der Relay unterscheidet einzelne
 * WS-Nachrichten bislang nicht nach Berechtigung, das Ticket gewaehrt effektiv volles
 * Lesen+Schreiben ueber den Kanal (bekannte Grenze, s. Project.md).
 */
@RestController
public class TicketController {

    private final TicketService ticketService;
    private final NoteRepository notes;
    private final VaultAccessGuard access;

    public TicketController(TicketService ticketService, NoteRepository notes, VaultAccessGuard access) {
        this.ticketService = ticketService;
        this.notes = notes;
        this.access = access;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/sync-tickets")
    public IssuedTicketResponse issueTicket(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        Authentication authentication
    ) {
        var actor = authentication.getName();
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        // Ohne diese Pruefung koennte jeder, der eine fremde NoteId kennt/eraet, ueber einen
        // beliebigen vaultId-Pfad ein gueltiges Sync-Ticket fuer sie bekommen und ihre volle
        // Yjs-Historie lesen/schreiben - Relay und SnapshotStore adressieren danach nur noch
        // ueber die NoteId, der Vault-Claim aus dem Ticket ist die einzige Durchsetzungsstelle.
        var note = notes.findById(vId, nId).orElseThrow(() -> new NoteNotFoundException(vId, nId));
        access.require(vId, actor, Permission.READ, note.path());

        var ticket = ticketService.issue(vId, nId, actor);
        return new IssuedTicketResponse(ticket.token(), ticket.expiresAt());
    }

    public record IssuedTicketResponse(String token, Instant expiresAt) {
    }
}
