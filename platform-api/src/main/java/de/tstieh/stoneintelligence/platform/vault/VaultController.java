package de.tstieh.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legt einen neuen Vault an und bootstrapt den anlegenden Actor automatisch mit vollen
 * Berechtigungen darin (eigene Rolle "owner" + eigene Gruppe "owners", Actor als einziges
 * Mitglied) - ohne diesen Schritt haette selbst der Ersteller nach der scharfen
 * Berechtigungsdurchsetzung (ADR 0006 Punkt 6, {@link VaultAccessGuard}) keinerlei Permission in
 * seinem eigenen, gerade erst angelegten Vault.
 */
@RestController
public class VaultController {

    private final VaultRepository vaults;
    private final AuthorizationRepository authorization;
    private final de.tstieh.stoneintelligence.platform.invitation.InvitationService invitations;

    private final VaultAccessGuard access;

    public VaultController(VaultRepository vaults, AuthorizationRepository authorization,
                           de.tstieh.stoneintelligence.platform.invitation.InvitationService invitations,
                           VaultAccessGuard access) {
        this.vaults = vaults;
        this.authorization = authorization;
        this.invitations = invitations;
        this.access = access;
    }

    @PostMapping("/api/v1/vaults")
    public VaultResponse create(@RequestBody CreateVaultRequest request, Authentication authentication) {
        var actor = authentication.getName();
        var vault = vaults.create(request.name());
        var ownerRole = authorization.createRole(vault.id(), "owner", EnumSet.allOf(Permission.class));
        var ownerGroup = authorization.createGroup(vault.id(), "owners");
        authorization.assignRole(ownerGroup.id(), ownerRole.id());
        authorization.addMember(ownerGroup.id(), actor);
        return VaultResponse.from(vault);
    }

    /**
     * Alle Vaults, in denen der authentifizierte Actor ueber irgendeine Gruppe Mitglied ist. Vorher
     * werden offene Einladungen an seine E-Mail-Adresse angenommen - aber NUR, wenn der Identity-
     * Provider die Adresse als bestaetigt ausweist ({@code email_verified=true}). Sonst koennte
     * sich jede Person, die ein Konto mit fremder Adresse anlegt, deren Einladungen aneignen; der
     * Weg ueber den Link aus der Mail bleibt davon unberuehrt.
     */
    @GetMapping("/api/v1/vaults")
    public List<VaultResponse> list(Authentication authentication) {
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt
                && Boolean.TRUE.equals(jwt.getToken().getClaimAsBoolean("email_verified"))) {
            invitations.acceptPendingForEmail(authentication.getName(), jwt.getToken().getClaimAsString("email"));
        }
        var accessibleIds = authorization.listAccessibleVaultIds(authentication.getName());
        return accessibleIds.stream()
            .map(vaults::findById)
            .flatMap(java.util.Optional::stream)
            .map(VaultResponse::from)
            .toList();
    }

    @GetMapping("/api/v1/vaults/{vaultId}/permissions")
    public java.util.Set<Permission> permissions(@org.springframework.web.bind.annotation.PathVariable String vaultId,
                                                 Authentication authentication) {
        return authorization.effectivePermissions(de.tstieh.stoneintelligence.domain.id.VaultId.of(vaultId),
            authentication.getName());
    }

    /** Umbenennen und/oder Arbeitsbereiche fuer alle Geraete vorgeben (ADR 0013) - nur mit Verwalten-Recht. */
    @org.springframework.web.bind.annotation.PatchMapping("/api/v1/vaults/{vaultId}")
    public VaultResponse rename(@org.springframework.web.bind.annotation.PathVariable String vaultId,
                                @RequestBody RenameVaultRequest request, Authentication authentication) {
        var vId = de.tstieh.stoneintelligence.domain.id.VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.MANAGE);
        if (request.name() == null && request.selectiveSync() == null) {
            throw new de.tstieh.stoneintelligence.platform.identity.InvalidGrantException("nothing to change");
        }
        if (request.name() != null && request.name().isBlank()) {
            throw new de.tstieh.stoneintelligence.platform.identity.InvalidGrantException("a vault needs a name");
        }
        var updated = request.name() == null ? vaults.findById(vId) : vaults.rename(vId, request.name().strip());
        if (request.selectiveSync() != null) {
            updated = vaults.setSelectiveSync(vId, request.selectiveSync());
        }
        return updated.map(VaultResponse::from).orElseThrow(() -> new ForbiddenException("no such vault"));
    }

    public record CreateVaultRequest(String name) {
    }

    public record RenameVaultRequest(String name, Boolean selectiveSync) {
        public RenameVaultRequest(String name) {
            this(name, null);
        }
    }

    public record VaultResponse(String id, String name, Instant createdAt, boolean selectiveSync) {
        static VaultResponse from(Vault vault) {
            return new VaultResponse(vault.id().value().toString(), vault.name(), vault.createdAt(), vault.selectiveSync());
        }
    }
}
