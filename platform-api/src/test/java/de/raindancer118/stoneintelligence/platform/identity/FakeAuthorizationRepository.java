package de.raindancer118.stoneintelligence.platform.identity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

public final class FakeAuthorizationRepository implements AuthorizationRepository {

    private record Scoped<T>(VaultId vaultId, T rule) {
    }

    private final Map<UUID, Role> roles = new HashMap<>();
    private final Map<UUID, Group> groups = new HashMap<>();
    private final Map<UUID, Set<UUID>> groupRoles = new HashMap<>();
    private final List<Scoped<PathRule>> pathRules = new ArrayList<>();
    private final List<Scoped<TopicRule>> topicRules = new ArrayList<>();

    @Override
    public synchronized Role createRole(VaultId vaultId, String name, Set<Permission> permissions) {
        var role = new Role(UUID.randomUUID(), vaultId, name, Set.copyOf(permissions));
        roles.put(role.id(), role);
        return role;
    }

    @Override
    public synchronized List<Role> listRoles(VaultId vaultId) {
        return roles.values().stream().filter(role -> role.vaultId().equals(vaultId)).toList();
    }

    @Override
    public synchronized Group createGroup(VaultId vaultId, String name) {
        var group = new Group(UUID.randomUUID(), vaultId, name, new LinkedHashSet<>());
        groups.put(group.id(), group);
        return group;
    }

    @Override
    public synchronized List<Group> listGroups(VaultId vaultId) {
        return groups.values().stream().filter(group -> group.vaultId().equals(vaultId)).toList();
    }

    @Override
    public synchronized boolean groupBelongsToVault(UUID groupId, VaultId vaultId) {
        var group = groups.get(groupId);
        return group != null && group.vaultId().equals(vaultId);
    }

    @Override
    public synchronized Set<UUID> listRoleIdsForGroup(UUID groupId) {
        return Set.copyOf(groupRoles.getOrDefault(groupId, Set.of()));
    }

    @Override
    public synchronized Map<UUID, Set<UUID>> listRoleIdsByGroup(VaultId vaultId) {
        return groups.values().stream()
            .filter(group -> group.vaultId().equals(vaultId))
            .filter(group -> !groupRoles.getOrDefault(group.id(), Set.of()).isEmpty())
            .collect(Collectors.toUnmodifiableMap(Group::id, group -> Set.copyOf(groupRoles.get(group.id()))));
    }

    @Override
    public synchronized Set<VaultId> listAccessibleVaultIds(String subject) {
        return groups.values().stream()
            .filter(group -> group.memberSubjects().contains(subject))
            .map(Group::vaultId)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized void addMember(UUID groupId, String subject) {
        groups.computeIfPresent(groupId, (id, group) -> {
            var members = new LinkedHashSet<>(group.memberSubjects());
            members.add(subject);
            return new Group(group.id(), group.vaultId(), group.name(), members);
        });
    }

    @Override
    public synchronized void removeMember(UUID groupId, String subject) {
        groups.computeIfPresent(groupId, (id, group) -> {
            var members = new LinkedHashSet<>(group.memberSubjects());
            members.remove(subject);
            return new Group(group.id(), group.vaultId(), group.name(), members);
        });
    }

    @Override
    public synchronized void assignRole(UUID groupId, UUID roleId) {
        var group = groups.get(groupId);
        if (group == null) {
            throw new IllegalArgumentException("no such group: " + groupId);
        }
        var role = roles.get(roleId);
        if (role == null) {
            throw new IllegalArgumentException("no such role: " + roleId);
        }
        if (!group.vaultId().equals(role.vaultId())) {
            throw new IllegalArgumentException(
                "group " + groupId + " (vault " + group.vaultId() + ") and role " + roleId
                    + " (vault " + role.vaultId() + ") belong to different vaults");
        }
        groupRoles.computeIfAbsent(groupId, id -> new HashSet<>()).add(roleId);
    }

    @Override
    public synchronized void unassignRole(UUID groupId, UUID roleId) {
        groupRoles.computeIfPresent(groupId, (id, roleIds) -> {
            roleIds.remove(roleId);
            return roleIds;
        });
    }

    @Override
    public synchronized Set<Permission> effectivePermissions(VaultId vaultId, String subject) {
        return groups.values().stream()
            .filter(group -> group.vaultId().equals(vaultId))
            .filter(group -> group.memberSubjects().contains(subject))
            .flatMap(group -> groupRoles.getOrDefault(group.id(), Set.of()).stream())
            .map(roles::get)
            .filter(java.util.Objects::nonNull)
            .filter(role -> role.vaultId().equals(vaultId))
            .flatMap(role -> role.permissions().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized PathRule createPathRule(VaultId vaultId, String pathPrefix, RuleScope scope, RuleEffect effect) {
        var rule = new PathRule(pathPrefix, scope, effect);
        pathRules.add(new Scoped<>(vaultId, rule));
        return rule;
    }

    @Override
    public synchronized List<PathRule> listPathRules(VaultId vaultId) {
        return pathRules.stream()
            .filter(scoped -> scoped.vaultId().equals(vaultId))
            .map(Scoped::rule)
            .toList();
    }

    @Override
    public synchronized TopicRule createTopicRule(VaultId vaultId, String topic, RuleScope scope, RuleEffect effect) {
        var rule = new TopicRule(topic, scope, effect);
        topicRules.add(new Scoped<>(vaultId, rule));
        return rule;
    }

    @Override
    public synchronized List<TopicRule> listTopicRules(VaultId vaultId) {
        return topicRules.stream()
            .filter(scoped -> scoped.vaultId().equals(vaultId))
            .map(Scoped::rule)
            .toList();
    }
}
