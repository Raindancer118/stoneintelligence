package de.tstieh.stoneintelligence.platform.identity;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.audit.AuditRecorder;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mitglieder, Rollen und Gruppen verwalten (Plan.md Abschnitt 4.4a, ADR 0011). Aendern verlangt
 * {@link Permission#MANAGE} im Vault; ansehen darf jedes Mitglied. Keine Aenderung darf den Vault
 * ohne verwaltende Person zuruecklassen ({@link LastManagerException}). Jede Aenderung an Rechten
 * wird protokolliert und den verbundenen Geraeten angekuendigt, damit offene Verbindungen sofort
 * nach den neuen Rechten laufen.
 *
 * <p>Die frueheren Pfadregel-Endpunkte sind entfallen (0.27.0) - Ordner und Eintraege gibt man
 * ueber {@link AccessController} frei.
 */
@RestController
public class AuthorizationController {

    private final AuthorizationRepository authorization;
    private final VaultAccessGuard access;
    private final AccessGrantRepository grants;
    private final NoteRepository notes;
    private final AuditRecorder audit;
    private final VaultAnnouncementService announcements;

    public AuthorizationController(AuthorizationRepository authorization, VaultAccessGuard access,
                                   AccessGrantRepository grants, NoteRepository notes, AuditRecorder audit,
                                   VaultAnnouncementService announcements) {
        this.authorization = authorization;
        this.access = access;
        this.grants = grants;
        this.notes = notes;
        this.audit = audit;
        this.announcements = announcements;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/roles")
    public List<RoleResponse> listRoles(@PathVariable String vaultId, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, authentication.getName());
        return authorization.listRoles(vId).stream().map(RoleResponse::from).toList();
    }

    @PostMapping("/api/v1/vaults/{vaultId}/roles")
    public RoleResponse createRole(
        @PathVariable String vaultId, @RequestBody CreateRoleRequest request, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.require(vId, authentication.getName(), Permission.MANAGE);
        var role = authorization.createRole(vId, request.name(), Set.copyOf(request.permissions()));
        return RoleResponse.from(role);
    }

    /** Name und/oder Rechte einer Rolle aendern; {@code null} laesst den Teil, wie er ist. */
    @PatchMapping("/api/v1/vaults/{vaultId}/roles/{roleId}")
    @Transactional
    public void updateRole(@PathVariable String vaultId, @PathVariable UUID roleId,
                           @RequestBody UpdateRoleRequest request, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        requireRoleAccess(vId, roleId, authentication);
        if (request.permissions() != null) {
            var permissions = request.permissions().isEmpty() ? Set.<Permission>of() : EnumSet.copyOf(request.permissions());
            requireManagerRemains(vId, snapshot -> snapshot.withRolePermissions(roleId, permissions));
            authorization.setRolePermissions(roleId, permissions);
        }
        if (request.name() != null && !request.name().isBlank()) {
            authorization.renameRole(roleId, request.name());
        }
        changed(vId, authentication, "ROLE_CHANGED", Map.of("roleId", roleId.toString()));
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/roles/{roleId}")
    @Transactional
    public void deleteRole(@PathVariable String vaultId, @PathVariable UUID roleId, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        requireRoleAccess(vId, roleId, authentication);
        requireManagerRemains(vId, snapshot -> snapshot.withoutRole(roleId));
        authorization.deleteRole(roleId);
        changed(vId, authentication, "ROLE_DELETED", Map.of("roleId", roleId.toString()));
    }

    @GetMapping("/api/v1/vaults/{vaultId}/groups")
    public List<GroupResponse> listGroups(@PathVariable String vaultId, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, authentication.getName());
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
        access.require(vId, authentication.getName(), Permission.MANAGE);
        var group = authorization.createGroup(vId, request.name());
        return GroupResponse.from(group, Set.of());
    }

    @PatchMapping("/api/v1/vaults/{vaultId}/groups/{groupId}")
    public void updateGroup(@PathVariable String vaultId, @PathVariable UUID groupId,
                            @RequestBody UpdateGroupRequest request, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        requireGroupAccess(vId, groupId, authentication);
        if (request.name() != null && !request.name().isBlank()) {
            authorization.renameGroup(groupId, request.name());
            audit.record(vId, null, authentication.getName(), "GROUP_CHANGED", Map.of("groupId", groupId.toString()));
        }
    }

    /** Entfernt die Gruppe samt Mitgliedschaften, Rollen-Zuweisungen und ihren Freigaben. */
    @DeleteMapping("/api/v1/vaults/{vaultId}/groups/{groupId}")
    @Transactional
    public void deleteGroup(@PathVariable String vaultId, @PathVariable UUID groupId, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        requireGroupAccess(vId, groupId, authentication);
        requireManagerRemains(vId, snapshot -> snapshot.withoutGroup(groupId));
        grants.removeGroup(vId, groupId);
        authorization.deleteGroup(groupId);
        changed(vId, authentication, "GROUP_DELETED", Map.of("groupId", groupId.toString()));
    }

    @PostMapping("/api/v1/vaults/{vaultId}/groups/{groupId}/members")
    public void addMember(
        @PathVariable String vaultId, @PathVariable UUID groupId,
        @RequestBody MemberRequest request, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        requireGroupAccess(vId, groupId, authentication);
        authorization.addMember(groupId, request.subject());
        changed(vId, authentication, "MEMBER_ADDED", Map.of("groupId", groupId.toString(), "subject", request.subject()));
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/groups/{groupId}/members/{subject}")
    @Transactional
    public void removeMember(
        @PathVariable String vaultId, @PathVariable UUID groupId,
        @PathVariable String subject, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        requireGroupAccess(vId, groupId, authentication);
        requireManagerRemains(vId, snapshot -> snapshot.withoutMember(groupId, subject));
        authorization.removeMember(groupId, subject);
        if (!authorization.membership(vId, subject).isMember()) {
            grants.removeSubject(vId, subject);
        }
        changed(vId, authentication, "MEMBER_LEFT_GROUP", Map.of("groupId", groupId.toString(), "subject", subject));
    }

    @PostMapping("/api/v1/vaults/{vaultId}/groups/{groupId}/roles/{roleId}")
    public void assignRole(
        @PathVariable String vaultId, @PathVariable UUID groupId,
        @PathVariable UUID roleId, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        requireGroupAccess(vId, groupId, authentication);
        authorization.assignRole(groupId, roleId);
        changed(vId, authentication, "ROLE_ASSIGNED", Map.of("groupId", groupId.toString(), "roleId", roleId.toString()));
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/groups/{groupId}/roles/{roleId}")
    @Transactional
    public void unassignRole(
        @PathVariable String vaultId, @PathVariable UUID groupId,
        @PathVariable UUID roleId, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        requireGroupAccess(vId, groupId, authentication);
        requireManagerRemains(vId, snapshot -> snapshot.withoutAssignment(groupId, roleId));
        authorization.unassignRole(groupId, roleId);
        changed(vId, authentication, "ROLE_UNASSIGNED", Map.of("groupId", groupId.toString(), "roleId", roleId.toString()));
    }

    /** Wer im Vault ist, ueber welche Gruppen und mit welchen Vault-Rechten. */
    @GetMapping("/api/v1/vaults/{vaultId}/members")
    public List<MemberResponse> listMembers(@PathVariable String vaultId, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, authentication.getName());
        var groups = authorization.listGroups(vId);
        return groups.stream().flatMap(group -> group.memberSubjects().stream()).distinct().sorted()
            .map(subject -> new MemberResponse(subject,
                groups.stream().filter(group -> group.memberSubjects().contains(subject))
                    .map(group -> new GroupRef(group.id().toString(), group.name())).toList(),
                authorization.membership(vId, subject).vaultPermissions()))
            .toList();
    }

    /**
     * Nimmt jemanden ganz aus dem Vault (alle Gruppen, persoenliche Freigaben). Das darf, wer
     * verwaltet - und jedes Mitglied fuer sich selbst ("Vault verlassen").
     */
    @DeleteMapping("/api/v1/vaults/{vaultId}/members/{subject}")
    @Transactional
    public void removeFromVault(@PathVariable String vaultId, @PathVariable String subject, Authentication authentication) {
        var vId = VaultId.of(vaultId);
        if (subject.equals(authentication.getName())) {
            access.requireMember(vId, subject);
        } else {
            access.require(vId, authentication.getName(), Permission.MANAGE);
        }
        requireManagerRemains(vId, snapshot -> snapshot.withoutSubject(subject));
        for (var group : authorization.listGroups(vId)) {
            if (group.memberSubjects().contains(subject)) {
                authorization.removeMember(group.id(), subject);
            }
        }
        grants.removeSubject(vId, subject);
        changed(vId, authentication, "MEMBER_REMOVED", Map.of("subject", subject));
    }

    private void requireGroupAccess(VaultId vaultId, UUID groupId, Authentication authentication) {
        access.require(vaultId, authentication.getName(), Permission.MANAGE);
        if (!authorization.groupBelongsToVault(groupId, vaultId)) {
            throw new ForbiddenException("group does not belong to this vault");
        }
    }

    private void requireRoleAccess(VaultId vaultId, UUID roleId, Authentication authentication) {
        access.require(vaultId, authentication.getName(), Permission.MANAGE);
        if (!authorization.roleBelongsToVault(roleId, vaultId)) {
            throw new ForbiddenException("role does not belong to this vault");
        }
    }

    private void requireManagerRemains(VaultId vaultId, UnaryOperator<AuthorizationSnapshot> change) {
        if (change.apply(AuthorizationSnapshot.of(authorization, vaultId)).managers().isEmpty()) {
            throw new LastManagerException();
        }
    }

    private void changed(VaultId vaultId, Authentication authentication, String action, Map<String, Object> payload) {
        audit.record(vaultId, null, authentication.getName(), action, payload);
        announcements.announceAccessChanged(vaultId);
    }

    public record CreateRoleRequest(String name, List<Permission> permissions) {
    }

    public record UpdateRoleRequest(String name, List<Permission> permissions) {
    }

    public record CreateGroupRequest(String name) {
    }

    public record UpdateGroupRequest(String name) {
    }

    public record MemberRequest(String subject) {
    }

    public record GroupRef(String id, String name) {
    }

    public record MemberResponse(String subject, List<GroupRef> groups, Set<Permission> permissions) {
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
}
