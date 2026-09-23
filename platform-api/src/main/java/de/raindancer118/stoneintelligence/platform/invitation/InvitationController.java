package de.raindancer118.stoneintelligence.platform.invitation;

import java.util.List;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Einladen (Vault-Verwaltung, MANAGE) und Einladung annehmen. {@code GET /api/v1/invitations/{token}}
 * ist bewusst ohne Login erreichbar (s. SecurityConfig): die eingeladene Person hat womoeglich noch
 * gar kein Konto und muss sehen, wozu sie eingeladen wurde.
 */
@RestController
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/people")
    public List<PersonSuggestion> searchPeople(@PathVariable String vaultId, @RequestParam(defaultValue = "") String q,
                                               Authentication authentication) {
        return invitations.searchPeople(VaultId.of(vaultId), authentication.getName(), q);
    }

    @PostMapping("/api/v1/vaults/{vaultId}/members")
    public InviteResult addMember(@PathVariable String vaultId, @RequestBody AddMemberRequest request, Authentication authentication) {
        return invitations.addExisting(VaultId.of(vaultId), authentication.getName(), request.username(), access(request.access()));
    }

    @PostMapping("/api/v1/vaults/{vaultId}/invitations")
    public InviteResult invite(@PathVariable String vaultId, @RequestBody InviteRequest request, Authentication authentication) {
        return invitations.inviteByEmail(VaultId.of(vaultId), authentication.getName(), request.email(), access(request.access()));
    }

    @GetMapping("/api/v1/vaults/{vaultId}/invitations")
    public List<PendingInvitation> listPending(@PathVariable String vaultId, Authentication authentication) {
        return invitations.listPending(VaultId.of(vaultId), authentication.getName());
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String vaultId, @PathVariable UUID invitationId, Authentication authentication) {
        invitations.revoke(VaultId.of(vaultId), authentication.getName(), invitationId);
    }

    @GetMapping("/api/v1/invitations/{token}")
    public InvitationInfo describe(@PathVariable String token) {
        return invitations.describe(token);
    }

    @PostMapping("/api/v1/invitations/{token}/accept")
    public JoinedVault accept(@PathVariable String token, Authentication authentication) {
        return invitations.accept(token, authentication.getName());
    }

    private static InviteAccess access(String value) {
        return "READ".equalsIgnoreCase(value) ? InviteAccess.READ : InviteAccess.EDIT;
    }

    public record AddMemberRequest(String username, String access) {
    }

    public record InviteRequest(String email, String access) {
    }
}
