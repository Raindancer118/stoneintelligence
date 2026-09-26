package de.raindancer118.stoneintelligence.platform.identity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rollen, Gruppen und Zuweisungen eines Vaults als unveraenderlicher Stand. Damit laesst sich vor
 * einer Aenderung pruefen, ob danach noch jemand den Vault verwalten kann ({@link #managers}) -
 * sonst koennte sich die letzte verwaltende Person selbst aussperren und der Vault waere
 * unverwaltbar (ADR 0011, "nicht aussperren").
 */
public record AuthorizationSnapshot(List<Role> roles, List<Group> groups, Map<UUID, Set<UUID>> roleIdsByGroup) {

    public static AuthorizationSnapshot of(AuthorizationRepository repository, de.raindancer118.stoneintelligence.domain.id.VaultId vaultId) {
        return new AuthorizationSnapshot(repository.listRoles(vaultId), repository.listGroups(vaultId), repository.listRoleIdsByGroup(vaultId));
    }

    /** Alle, die ueber irgendeine Gruppe eine Rolle mit {@link Permission#MANAGE} haben. */
    public Set<String> managers() {
        var managingRoles = roles.stream()
            .filter(role -> role.permissions().contains(Permission.MANAGE))
            .map(Role::id)
            .collect(Collectors.toSet());
        return groups.stream()
            .filter(group -> roleIdsByGroup.getOrDefault(group.id(), Set.of()).stream().anyMatch(managingRoles::contains))
            .flatMap(group -> group.memberSubjects().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    public AuthorizationSnapshot withoutMember(UUID groupId, String subject) {
        return new AuthorizationSnapshot(roles, groups.stream()
            .map(group -> group.id().equals(groupId) ? withoutSubject(group, subject) : group)
            .toList(), roleIdsByGroup);
    }

    /** Die Person verlaesst den Vault ganz (alle Gruppen). */
    public AuthorizationSnapshot withoutSubject(String subject) {
        return new AuthorizationSnapshot(roles, groups.stream().map(group -> withoutSubject(group, subject)).toList(), roleIdsByGroup);
    }

    public AuthorizationSnapshot withoutAssignment(UUID groupId, UUID roleId) {
        var assignments = new HashMap<>(roleIdsByGroup);
        assignments.computeIfPresent(groupId, (id, roleIds) ->
            roleIds.stream().filter(other -> !other.equals(roleId)).collect(Collectors.toUnmodifiableSet()));
        return new AuthorizationSnapshot(roles, groups, assignments);
    }

    public AuthorizationSnapshot withRolePermissions(UUID roleId, Set<Permission> permissions) {
        return new AuthorizationSnapshot(roles.stream()
            .map(role -> role.id().equals(roleId) ? new Role(role.id(), role.vaultId(), role.name(), permissions) : role)
            .toList(), groups, roleIdsByGroup);
    }

    public AuthorizationSnapshot withoutRole(UUID roleId) {
        return new AuthorizationSnapshot(roles.stream().filter(role -> !role.id().equals(roleId)).toList(), groups, roleIdsByGroup);
    }

    public AuthorizationSnapshot withoutGroup(UUID groupId) {
        return new AuthorizationSnapshot(roles, groups.stream().filter(group -> !group.id().equals(groupId)).toList(), roleIdsByGroup);
    }

    private static Group withoutSubject(Group group, String subject) {
        return new Group(group.id(), group.vaultId(), group.name(),
            group.memberSubjects().stream().filter(member -> !member.equals(subject)).collect(Collectors.toUnmodifiableSet()));
    }
}
