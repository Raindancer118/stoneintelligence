package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import de.raindancer118.stoneintelligence.platform.identity.AuthorizationRepository;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
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

    public VaultController(VaultRepository vaults, AuthorizationRepository authorization) {
        this.vaults = vaults;
        this.authorization = authorization;
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

    /** Alle Vaults, in denen der authentifizierte Actor ueber irgendeine Gruppe Mitglied ist. */
    @GetMapping("/api/v1/vaults")
    public List<VaultResponse> list(Authentication authentication) {
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
        return authorization.effectivePermissions(de.raindancer118.stoneintelligence.domain.id.VaultId.of(vaultId),
            authentication.getName());
    }

    public record CreateVaultRequest(String name) {
    }

    public record VaultResponse(String id, String name, Instant createdAt) {
        static VaultResponse from(Vault vault) {
            return new VaultResponse(vault.id().value().toString(), vault.name(), vault.createdAt());
        }
    }
}
