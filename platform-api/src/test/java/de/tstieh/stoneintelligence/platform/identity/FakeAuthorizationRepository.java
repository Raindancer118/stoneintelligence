package de.tstieh.stoneintelligence.platform.identity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public class FakeAuthorizationRepository implements AuthorizationRepository {

    private record Scoped<T>(VaultId vaultId, T rule) {
    }

    private final Map<UUID, Role> roles = new HashMap<>();
    private final Map<UUID, Group> groups = new HashMap<>();
    private final Map<UUID, Set<UUID>> groupRoles = new HashMap<>();
    private final List<Scoped<TopicRule>> topicRules = new ArrayList<>();
    /** subject -> IdP-Gruppe -> zuletzt im Token gesehen. */
    private final Map<String, Map<String, Instant>> tokenGroups = new HashMap<>();
    /** Per SCIM verwaltete Konten: subject -> Schluessel ihrer SCIM-Gruppen (s. {@code FakeDirectoryStore}). */
    private Map<String, Set<String>> scimGroups = Map.of();
    private Set<String> blocked = Set.of();

    /** Stand des SCIM-Verzeichnisses, wie ihn die Datenbank-Sicht {@code effective_group_members} sieht. */
    public synchronized void directory(Map<String, Set<String>> scimGroupsBySubject, Set<String> blockedSubjects) {
        this.scimGroups = Map.copyOf(scimGroupsBySubject);
        this.blocked = Set.copyOf(blockedSubjects);
    }

    private boolean isMember(Group group, String subject) {
        if (blocked.contains(subject)) {
            return false;
        }
        if (group.memberSubjects().contains(subject)) {
            return true;
        }
        return group.idpGroup() != null && idpGroupsOf(subject).contains(group.idpGroup());
    }

    private Set<String> idpGroupsOf(String subject) {
        var scim = scimGroups.get(subject);
        return scim != null ? scim : validTokenGroups(subject);
    }

    private Set<String> validTokenGroups(String subject) {
        var cutoff = Instant.now().minus(TOKEN_GROUPS_VALID_FOR);
        return tokenGroups.getOrDefault(subject, Map.of()).entrySet().stream()
            .filter(entry -> entry.getValue().isAfter(cutoff))
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized Set<VaultId> vaultsLinking(Set<String> idpGroups) {
        return groups.values().stream()
            .filter(group -> group.idpGroup() != null && idpGroups.contains(group.idpGroup()))
            .map(Group::vaultId)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> symmetricDifference(Set<String> a, Set<String> b) {
        var result = new HashSet<String>(a);
        result.addAll(b);
        var both = new HashSet<String>(a);
        both.retainAll(b);
        result.removeAll(both);
        return result;
    }

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
            .filter(group -> isMember(group, subject))
            .map(Group::vaultId)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized void addMember(UUID groupId, String subject) {
        groups.computeIfPresent(groupId, (id, group) -> {
            var members = new LinkedHashSet<>(group.memberSubjects());
            members.add(subject);
            return new Group(group.id(), group.vaultId(), group.name(), members, group.idpGroup());
        });
    }

    @Override
    public synchronized void removeMember(UUID groupId, String subject) {
        groups.computeIfPresent(groupId, (id, group) -> {
            var members = new LinkedHashSet<>(group.memberSubjects());
            members.remove(subject);
            return new Group(group.id(), group.vaultId(), group.name(), members, group.idpGroup());
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
            .filter(group -> isMember(group, subject))
            .flatMap(group -> groupRoles.getOrDefault(group.id(), Set.of()).stream())
            .map(roles::get)
            .filter(java.util.Objects::nonNull)
            .filter(role -> role.vaultId().equals(vaultId))
            .flatMap(role -> role.permissions().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized Membership membership(VaultId vaultId, String subject) {
        var groupIds = groups.values().stream()
            .filter(group -> group.vaultId().equals(vaultId) && isMember(group, subject))
            .map(Group::id)
            .collect(Collectors.toUnmodifiableSet());
        return new Membership(subject, groupIds, effectivePermissions(vaultId, subject));
    }

    @Override
    public synchronized boolean roleBelongsToVault(UUID roleId, VaultId vaultId) {
        var role = roles.get(roleId);
        return role != null && role.vaultId().equals(vaultId);
    }

    @Override
    public synchronized void renameRole(UUID roleId, String name) {
        roles.computeIfPresent(roleId, (id, role) -> new Role(id, role.vaultId(), name, role.permissions()));
    }

    @Override
    public synchronized void setRolePermissions(UUID roleId, Set<Permission> permissions) {
        roles.computeIfPresent(roleId, (id, role) -> new Role(id, role.vaultId(), role.name(), Set.copyOf(permissions)));
    }

    @Override
    public synchronized void deleteRole(UUID roleId) {
        roles.remove(roleId);
        groupRoles.values().forEach(roleIds -> roleIds.remove(roleId));
    }

    @Override
    public synchronized void renameGroup(UUID groupId, String name) {
        groups.computeIfPresent(groupId, (id, group) -> new Group(id, group.vaultId(), name, group.memberSubjects(), group.idpGroup()));
    }

    @Override
    public synchronized void deleteGroup(UUID groupId) {
        groups.remove(groupId);
        groupRoles.remove(groupId);
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

    @Override
    public synchronized void linkIdpGroup(UUID groupId, String idpGroup) {
        var group = groups.get(groupId);
        if (group == null) {
            return;
        }
        if (idpGroup != null && groups.values().stream().anyMatch(other -> !other.id().equals(groupId)
                && other.vaultId().equals(group.vaultId()) && idpGroup.equals(other.idpGroup()))) {
            throw new IdpGroupAlreadyLinkedException(idpGroup);
        }
        groups.put(groupId, new Group(groupId, group.vaultId(), group.name(), group.memberSubjects(), idpGroup));
    }

    @Override
    public synchronized Set<String> linkedIdpGroups() {
        return groups.values().stream().map(Group::idpGroup).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized Set<VaultId> rememberTokenGroups(String subject, Set<String> idpGroups, Instant seenAt) {
        var before = validTokenGroups(subject);
        var linked = linkedIdpGroups();
        var remembered = new HashMap<String, Instant>();
        idpGroups.stream().filter(linked::contains).forEach(group -> remembered.put(group, seenAt));
        tokenGroups.put(subject, remembered);
        if (scimGroups.containsKey(subject)) {
            return Set.of();
        }
        return vaultsLinking(symmetricDifference(before, validTokenGroups(subject)));
    }

    @Override
    public synchronized Set<VaultId> forgetTokenGroupsSeenBefore(Instant before) {
        var forgotten = new HashSet<String>();
        for (var entry : tokenGroups.entrySet()) {
            var stale = entry.getValue().entrySet().stream().filter(seen -> seen.getValue().isBefore(before))
                .map(Map.Entry::getKey).toList();
            stale.forEach(entry.getValue()::remove);
            forgotten.addAll(stale);
        }
        return vaultsLinking(forgotten);
    }

    @Override
    public synchronized Map<UUID, Integer> countIdpMembers(VaultId vaultId) {
        var subjects = new HashSet<String>(tokenGroups.keySet());
        subjects.addAll(scimGroups.keySet());
        var counts = new HashMap<UUID, Integer>();
        for (var group : groups.values()) {
            if (group.vaultId().equals(vaultId) && group.idpGroup() != null) {
                var count = (int) subjects.stream()
                    .filter(subject -> !blocked.contains(subject) && idpGroupsOf(subject).contains(group.idpGroup())).count();
                counts.put(group.id(), count);
            }
        }
        return counts;
    }
}
