package de.tstieh.stoneintelligence.platform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAuthorizationRepository implements AuthorizationRepository {

    private final JdbcClient jdbcClient;

    public JdbcAuthorizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public Role createRole(VaultId vaultId, String name, Set<Permission> permissions) {
        var roleId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO platform.roles (id, vault_id, name) VALUES (:id, :vaultId, :name)")
            .param("id", roleId)
            .param("vaultId", vaultId.value())
            .param("name", name)
            .update();

        for (var permission : permissions) {
            jdbcClient.sql("INSERT INTO platform.role_permissions (role_id, permission) VALUES (:roleId, :permission)")
                .param("roleId", roleId)
                .param("permission", permission.name())
                .update();
        }

        return new Role(roleId, vaultId, name, Set.copyOf(permissions));
    }

    @Override
    public List<Role> listRoles(VaultId vaultId) {
        var permissionsByRoleId = new HashMap<UUID, Set<Permission>>();
        jdbcClient.sql("""
                SELECT rp.role_id, rp.permission FROM platform.role_permissions rp
                JOIN platform.roles r ON r.id = rp.role_id WHERE r.vault_id = :vaultId
                """)
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> Map.entry(
                (UUID) rs.getObject("role_id"), Permission.valueOf(rs.getString("permission"))))
            .list()
            .forEach(entry -> permissionsByRoleId
                .computeIfAbsent(entry.getKey(), id -> new LinkedHashSet<>()).add(entry.getValue()));

        return jdbcClient.sql("SELECT id, name FROM platform.roles WHERE vault_id = :vaultId ORDER BY name")
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> {
                var id = (UUID) rs.getObject("id");
                return new Role(id, vaultId, rs.getString("name"), permissionsByRoleId.getOrDefault(id, Set.of()));
            })
            .list();
    }

    @Override
    public Group createGroup(VaultId vaultId, String name) {
        var groupId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO platform.groups (id, vault_id, name) VALUES (:id, :vaultId, :name)")
            .param("id", groupId)
            .param("vaultId", vaultId.value())
            .param("name", name)
            .update();
        return new Group(groupId, vaultId, name, Set.of());
    }

    @Override
    public List<Group> listGroups(VaultId vaultId) {
        var membersByGroupId = new HashMap<UUID, Set<String>>();
        jdbcClient.sql("""
                SELECT gm.group_id, gm.subject FROM platform.group_members gm
                JOIN platform.groups g ON g.id = gm.group_id WHERE g.vault_id = :vaultId
                """)
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> Map.entry((UUID) rs.getObject("group_id"), rs.getString("subject")))
            .list()
            .forEach(entry -> membersByGroupId
                .computeIfAbsent(entry.getKey(), id -> new LinkedHashSet<>()).add(entry.getValue()));

        return jdbcClient.sql("SELECT id, name, idp_group FROM platform.groups WHERE vault_id = :vaultId ORDER BY name")
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> {
                var id = (UUID) rs.getObject("id");
                return new Group(id, vaultId, rs.getString("name"), membersByGroupId.getOrDefault(id, Set.of()), rs.getString("idp_group"));
            })
            .list();
    }

    @Override
    public boolean groupBelongsToVault(UUID groupId, VaultId vaultId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM platform.groups WHERE id = :groupId AND vault_id = :vaultId)")
            .param("groupId", groupId)
            .param("vaultId", vaultId.value())
            .query(Boolean.class)
            .single();
    }

    @Override
    public Set<UUID> listRoleIdsForGroup(UUID groupId) {
        return Set.copyOf(jdbcClient.sql("SELECT role_id FROM platform.group_roles WHERE group_id = :groupId")
            .param("groupId", groupId)
            .query(UUID.class)
            .list());
    }

    @Override
    public Map<UUID, Set<UUID>> listRoleIdsByGroup(VaultId vaultId) {
        var rolesByGroup = new HashMap<UUID, Set<UUID>>();
        jdbcClient.sql("""
                SELECT gr.group_id, gr.role_id FROM platform.group_roles gr
                JOIN platform.groups g ON g.id = gr.group_id
                WHERE g.vault_id = :vaultId
                """)
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> Map.entry(
                (UUID) rs.getObject("group_id"), (UUID) rs.getObject("role_id")))
            .list()
            .forEach(entry -> rolesByGroup
                .computeIfAbsent(entry.getKey(), id -> new LinkedHashSet<>()).add(entry.getValue()));
        return rolesByGroup;
    }

    @Override
    public Set<VaultId> listAccessibleVaultIds(String subject) {
        var ids = jdbcClient.sql("""
                SELECT DISTINCT g.vault_id FROM platform.effective_group_members gm
                JOIN platform.groups g ON g.id = gm.group_id
                WHERE gm.subject = :subject
                """)
            .param("subject", subject)
            .query(UUID.class)
            .list();
        var result = new HashSet<VaultId>();
        for (var id : ids) {
            result.add(VaultId.of(id));
        }
        return Set.copyOf(result);
    }

    @Override
    public void addMember(UUID groupId, String subject) {
        jdbcClient.sql("""
                INSERT INTO platform.group_members (group_id, subject) VALUES (:groupId, :subject)
                ON CONFLICT DO NOTHING
                """)
            .param("groupId", groupId)
            .param("subject", subject)
            .update();
    }

    @Override
    public void removeMember(UUID groupId, String subject) {
        jdbcClient.sql("DELETE FROM platform.group_members WHERE group_id = :groupId AND subject = :subject")
            .param("groupId", groupId)
            .param("subject", subject)
            .update();
    }

    @Override
    @Transactional
    public void assignRole(UUID groupId, UUID roleId) {
        var groupVaultId = jdbcClient.sql("SELECT vault_id FROM platform.groups WHERE id = :groupId")
            .param("groupId", groupId)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("no such group: " + groupId));
        var roleVaultId = jdbcClient.sql("SELECT vault_id FROM platform.roles WHERE id = :roleId")
            .param("roleId", roleId)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("no such role: " + roleId));
        // Ohne diese Pruefung koennte eine Rolle aus einem ANDEREN Vault einer Gruppe zugewiesen
        // werden - effectivePermissions() wuerde deren Berechtigungen dann faelschlich gewaehren,
        // sobald ACL-Durchsetzung verdrahtet ist (Cross-Vault-Rechte-Import).
        if (!groupVaultId.equals(roleVaultId)) {
            throw new IllegalArgumentException(
                "group " + groupId + " (vault " + groupVaultId + ") and role " + roleId
                    + " (vault " + roleVaultId + ") belong to different vaults");
        }

        jdbcClient.sql("""
                INSERT INTO platform.group_roles (group_id, role_id) VALUES (:groupId, :roleId)
                ON CONFLICT DO NOTHING
                """)
            .param("groupId", groupId)
            .param("roleId", roleId)
            .update();
    }

    @Override
    public void unassignRole(UUID groupId, UUID roleId) {
        jdbcClient.sql("DELETE FROM platform.group_roles WHERE group_id = :groupId AND role_id = :roleId")
            .param("groupId", groupId)
            .param("roleId", roleId)
            .update();
    }

    @Override
    public Set<Permission> effectivePermissions(VaultId vaultId, String subject) {
        var names = jdbcClient.sql("""
                SELECT DISTINCT rp.permission
                FROM platform.effective_group_members gm
                JOIN platform.groups g ON g.id = gm.group_id AND g.vault_id = :vaultId
                JOIN platform.group_roles gr ON gr.group_id = g.id
                JOIN platform.roles r ON r.id = gr.role_id AND r.vault_id = :vaultId
                JOIN platform.role_permissions rp ON rp.role_id = gr.role_id
                WHERE gm.subject = :subject
                """)
            .param("vaultId", vaultId.value())
            .param("subject", subject)
            .query(String.class)
            .list();

        var permissions = new LinkedHashSet<Permission>();
        for (var name : names) {
            permissions.add(Permission.valueOf(name));
        }
        return Set.copyOf(permissions);
    }

    private static RuleScope scopeOf(String scopeType, String scopeSubject) {
        return "USER".equals(scopeType) ? RuleScope.user(scopeSubject) : RuleScope.everyone();
    }

    private static final RowMapper<TopicRule> TOPIC_RULE_MAPPER = (rs, rowNum) -> new TopicRule(
        rs.getString("topic"),
        scopeOf(rs.getString("scope_type"), rs.getString("scope_subject")),
        RuleEffect.valueOf(rs.getString("effect"))
    );

    @Override
    public Membership membership(VaultId vaultId, String subject) {
        var groupIds = new java.util.LinkedHashSet<UUID>();
        var permissions = java.util.EnumSet.noneOf(Permission.class);
        jdbcClient.sql("""
                SELECT gm.group_id, rp.permission
                FROM platform.effective_group_members gm
                JOIN platform.groups g ON g.id = gm.group_id AND g.vault_id = :vaultId
                LEFT JOIN platform.group_roles gr ON gr.group_id = g.id
                LEFT JOIN platform.roles r ON r.id = gr.role_id AND r.vault_id = :vaultId
                LEFT JOIN platform.role_permissions rp ON rp.role_id = r.id
                WHERE gm.subject = :subject
                """)
            .param("vaultId", vaultId.value())
            .param("subject", subject)
            .query((rs, rowNum) -> {
                groupIds.add((UUID) rs.getObject("group_id"));
                var permission = rs.getString("permission");
                if (permission != null) {
                    permissions.add(Permission.valueOf(permission));
                }
                return null;
            })
            .list();
        return new Membership(subject, groupIds, permissions);
    }

    @Override
    public boolean roleBelongsToVault(UUID roleId, VaultId vaultId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM platform.roles WHERE id = :roleId AND vault_id = :vaultId)")
            .param("roleId", roleId)
            .param("vaultId", vaultId.value())
            .query(Boolean.class)
            .single();
    }

    @Override
    public void renameRole(UUID roleId, String name) {
        jdbcClient.sql("UPDATE platform.roles SET name = :name WHERE id = :roleId")
            .param("roleId", roleId)
            .param("name", name)
            .update();
    }

    @Override
    @Transactional
    public void setRolePermissions(UUID roleId, Set<Permission> permissions) {
        jdbcClient.sql("DELETE FROM platform.role_permissions WHERE role_id = :roleId").param("roleId", roleId).update();
        for (var permission : permissions) {
            jdbcClient.sql("INSERT INTO platform.role_permissions (role_id, permission) VALUES (:roleId, :permission)")
                .param("roleId", roleId)
                .param("permission", permission.name())
                .update();
        }
    }

    @Override
    public void deleteRole(UUID roleId) {
        jdbcClient.sql("DELETE FROM platform.roles WHERE id = :roleId").param("roleId", roleId).update();
    }

    @Override
    public void renameGroup(UUID groupId, String name) {
        jdbcClient.sql("UPDATE platform.groups SET name = :name WHERE id = :groupId")
            .param("groupId", groupId)
            .param("name", name)
            .update();
    }

    @Override
    public void deleteGroup(UUID groupId) {
        jdbcClient.sql("DELETE FROM platform.groups WHERE id = :groupId").param("groupId", groupId).update();
    }

    @Override
    public TopicRule createTopicRule(VaultId vaultId, String topic, RuleScope scope, RuleEffect effect) {
        jdbcClient.sql("""
                INSERT INTO platform.topic_rules (vault_id, topic, scope_type, scope_subject, effect)
                VALUES (:vaultId, :topic, :scopeType, :scopeSubject, :effect)
                """)
            .param("vaultId", vaultId.value())
            .param("topic", topic)
            .param("scopeType", scopeTypeOf(scope))
            .param("scopeSubject", scopeSubjectOf(scope))
            .param("effect", effect.name())
            .update();
        return new TopicRule(topic, scope, effect);
    }

    @Override
    public List<TopicRule> listTopicRules(VaultId vaultId) {
        return jdbcClient.sql("SELECT * FROM platform.topic_rules WHERE vault_id = :vaultId ORDER BY id")
            .param("vaultId", vaultId.value())
            .query(TOPIC_RULE_MAPPER)
            .list();
    }

    private static String scopeTypeOf(RuleScope scope) {
        return scope instanceof RuleScope.User ? "USER" : "EVERYONE";
    }

    private static String scopeSubjectOf(RuleScope scope) {
        return scope instanceof RuleScope.User user ? user.subject() : null;
    }

    @Override
    public void linkIdpGroup(UUID groupId, String idpGroup) {
        try {
            jdbcClient.sql("UPDATE platform.groups SET idp_group = :idpGroup WHERE id = :groupId")
                .param("groupId", groupId)
                .param("idpGroup", idpGroup)
                .update();
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new IdpGroupAlreadyLinkedException(idpGroup);
        }
    }

    @Override
    public Set<String> linkedIdpGroups() {
        return Set.copyOf(jdbcClient.sql("SELECT DISTINCT idp_group FROM platform.groups WHERE idp_group IS NOT NULL")
            .query(String.class).list());
    }

    /**
     * Nur geaenderte Zeilen anfassen: Einfuegen/Loeschen loest Trigger aus (Rechte-Cache, Feed), Auffrischen nicht.
     * Abgelaufene Zeilen zaehlen als nicht vorhanden - wer nach Ablauf wiederkommt, tritt neu bei.
     */
    @Override
    @Transactional
    public Set<VaultId> rememberTokenGroups(String subject, Set<String> idpGroups, Instant seenAt) {
        var cutoff = Timestamp.from(Instant.now().minus(TOKEN_GROUPS_VALID_FOR));
        var before = Set.copyOf(jdbcClient.sql("""
                SELECT idp_group FROM platform.subject_idp_groups WHERE subject = :subject AND seen_at > :cutoff
                """)
            .param("subject", subject).param("cutoff", cutoff).query(String.class).list());
        var linked = linkedIdpGroups();
        var remembered = idpGroups.stream().filter(linked::contains).toList();
        jdbcClient.sql("DELETE FROM platform.subject_idp_groups WHERE subject = :subject AND NOT (idp_group = ANY (:groups))")
            .param("subject", subject)
            .param("groups", remembered.toArray(String[]::new))
            .update();
        for (var group : remembered) {
            jdbcClient.sql("""
                    INSERT INTO platform.subject_idp_groups (subject, idp_group, seen_at) VALUES (:subject, :group, :seenAt)
                    ON CONFLICT (subject, idp_group) DO UPDATE SET seen_at = EXCLUDED.seen_at
                    """)
                .param("subject", subject)
                .param("group", group)
                .param("seenAt", Timestamp.from(seenAt))
                .update();
        }
        var after = Set.copyOf(jdbcClient.sql("""
                SELECT idp_group FROM platform.subject_idp_groups WHERE subject = :subject AND seen_at > :cutoff
                """)
            .param("subject", subject).param("cutoff", cutoff).query(String.class).list());
        var changed = new java.util.HashSet<>(before);
        changed.addAll(after);
        changed.removeIf(group -> before.contains(group) && after.contains(group));
        if (changed.isEmpty() || isDirectoryManaged(subject)) {
            return Set.of();
        }
        return vaultsLinking(changed);
    }

    @Override
    public Set<VaultId> forgetTokenGroupsSeenBefore(Instant before) {
        var forgotten = jdbcClient.sql("DELETE FROM platform.subject_idp_groups WHERE seen_at < :before RETURNING idp_group")
            .param("before", Timestamp.from(before))
            .query(String.class).list();
        return forgotten.isEmpty() ? Set.of() : vaultsLinking(Set.copyOf(forgotten));
    }

    @Override
    public Map<UUID, Integer> countIdpMembers(VaultId vaultId) {
        var counts = new java.util.HashMap<UUID, Integer>();
        jdbcClient.sql("""
                SELECT g.id, count(e.subject) AS members FROM platform.groups g
                  LEFT JOIN platform.effective_group_members e ON e.group_id = g.id
                   AND NOT EXISTS (SELECT 1 FROM platform.group_members gm WHERE gm.group_id = g.id AND gm.subject = e.subject)
                 WHERE g.vault_id = :vaultId AND g.idp_group IS NOT NULL
                 GROUP BY g.id
                """)
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> counts.put((UUID) rs.getObject("id"), rs.getInt("members")))
            .list();
        return counts;
    }

    private boolean isDirectoryManaged(String subject) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM platform.directory_users WHERE subject = :subject)")
            .param("subject", subject).query(Boolean.class).single();
    }

    @Override
    public Set<VaultId> vaultsLinking(Set<String> idpGroups) {
        return jdbcClient.sql("SELECT DISTINCT vault_id FROM platform.groups WHERE idp_group = ANY (:groups)")
            .param("groups", idpGroups.toArray(String[]::new))
            .query((rs, rowNum) -> VaultId.of(rs.getObject("vault_id").toString()))
            .list().stream().collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
