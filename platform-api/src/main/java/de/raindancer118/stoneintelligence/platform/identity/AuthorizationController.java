package de.raindancer118.stoneintelligence.platform.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import de.raindancer118.stoneintelligence.platform.vault.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rollen/Gruppen/Ordner-ACL-Verwaltung (Plan.md Abschnitt 4.4a). {@link VaultAccessGuard} kennt
 * kein eigenes "Admin"-Permission - Verwaltungsoperationen verlangen bewusst
 * {@link Permission#DELETE} als Stellvertreter (staerkste der vier bestehenden Permissions,
 * keine Schema-Aenderung fuer eine fuenfte noetig). Die Owner-Rolle, die
 * {@code VaultController} beim Anlegen automatisch vergibt, hat alle vier Permissions.
 */
@RestController
public class AuthorizationController {

    private final AuthorizationRepository authorization;
    private final VaultAccessGuard access;

    public AuthorizationController(AuthorizationRepository authorization, VaultAccessGuard access) {
        this.authorization = authorization;
        this.access = access;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/roles")
    public List<RoleResponse> listRoles(@PathVariable String vaultId, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.READ);
        return authorization.listRoles(vId).stream().map(RoleResponse::from).toList();
    }

    @PostMapping("/api/v1/vaults/{vaultId}/roles")
    public RoleResponse createRole(
        @PathVariable String vaultId, @RequestBody CreateRoleRequest request, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.DELETE);
        var role = authorization.createRole(vId, request.name(), Set.copyOf(request.permissions()));
        return RoleResponse.from(role);
    }

    @GetMapping("/api/v1/vaults/{vaultId}/groups")
    public List<GroupResponse> listGroups(@PathVariable String vaultId, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.READ);
        var rolesByGroup = authorization.listRoleIdsByGroup(vId);
        return authorization.listGroups(vId).stream()
            .map(group -> GroupResponse.from(group, rolesByGroup.getOrDefault(group.id(), Set.of())))
            .toList();
    }

    @PostMapping("/api/v1/vaults/{vaultId}/groups")
    public GroupResponse createGroup(
        @PathVariable String vaultId, @RequestBody CreateGroupRequest request, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.DELETE);
        var group = authorization.createGroup(vId, request.name());
        return GroupResponse.from(group, Set.of());
    }

    @PostMapping("/api/v1/vaults/{vaultId}/groups/{groupId}/members")
    public void addMember(
        @PathVariable String vaultId, @PathVariable UUID groupId,
        @RequestBody MemberRequest request, Authentication authentication
    ) {
        requireGroupAccess(VaultId.of(vaultId), groupId, authentication);
        authorization.addMember(groupId, request.subject());
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/groups/{groupId}/members/{subject}")
    public void removeMember(
        @PathVariable String vaultId, @PathVariable UUID groupId,
        @PathVariable String subject, Authentication authentication
    ) {
        requireGroupAccess(VaultId.of(vaultId), groupId, authentication);
        authorization.removeMember(groupId, subject);
    }

    @PostMapping("/api/v1/vaults/{vaultId}/groups/{groupId}/roles/{roleId}")
    public void assignRole(
        @PathVariable String vaultId, @PathVariable UUID groupId,
        @PathVariable UUID roleId, Authentication authentication
    ) {
        requireGroupAccess(VaultId.of(vaultId), groupId, authentication);
        authorization.assignRole(groupId, roleId);
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/groups/{groupId}/roles/{roleId}")
    public void unassignRole(
        @PathVariable String vaultId, @PathVariable UUID groupId,
        @PathVariable UUID roleId, Authentication authentication
    ) {
        requireGroupAccess(VaultId.of(vaultId), groupId, authentication);
        authorization.unassignRole(groupId, roleId);
    }

    private void requireGroupAccess(VaultId vaultId, UUID groupId, Authentication authentication) {
        access.require(vaultId, authentication.getName(), Permission.DELETE);
        if (!authorization.groupBelongsToVault(groupId, vaultId)) {
            throw new ForbiddenException("group does not belong to this vault");
        }
    }

    @GetMapping("/api/v1/vaults/{vaultId}/path-rules")
    public List<PathRuleResponse> listPathRules(@PathVariable String vaultId, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.READ);
        return authorization.listPathRules(vId).stream().map(PathRuleResponse::from).toList();
    }

    @PostMapping("/api/v1/vaults/{vaultId}/path-rules")
    public PathRuleResponse createPathRule(
        @PathVariable String vaultId, @RequestBody CreatePathRuleRequest request, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.DELETE);
        var scope = request.scopeSubject() == null || request.scopeSubject().isBlank()
            ? RuleScope.everyone()
            : RuleScope.user(request.scopeSubject());
        var rule = authorization.createPathRule(vId, request.pathPrefix(), scope, RuleEffect.valueOf(request.effect()));
        return PathRuleResponse.from(rule);
    }

    public record CreateRoleRequest(String name, List<Permission> permissions) {
    }

    public record CreateGroupRequest(String name) {
    }

    public record MemberRequest(String subject) {
    }

    public record CreatePathRuleRequest(String pathPrefix, String scopeSubject, String effect) {
    }

    public record RoleResponse(String id, String name, Set<Permission> permissions) {
        static RoleResponse from(Role role) {
            return new RoleResponse(role.id().toString(), role.name(), role.permissions());
        }
    }

    public record GroupResponse(String id, String name, Set<String> memberSubjects, Set<String> roleIds) {
        static GroupResponse from(Group group, Set<UUID> roleIds) {
            return new GroupResponse(
                group.id().toString(), group.name(), group.memberSubjects(),
                roleIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }

    public record PathRuleResponse(String pathPrefix, String scopeSubject, String effect) {
        static PathRuleResponse from(PathRule rule) {
            var scopeSubject = rule.scope() instanceof RuleScope.User user ? user.subject() : null;
            return new PathRuleResponse(rule.pathPrefix(), scopeSubject, rule.effect().name());
        }
    }
}
