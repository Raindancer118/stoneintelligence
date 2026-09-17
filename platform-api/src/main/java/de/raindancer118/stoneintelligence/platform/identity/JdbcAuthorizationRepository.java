package de.raindancer118.stoneintelligence.platform.identity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
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

        return jdbcClient.sql("SELECT id, name FROM platform.groups WHERE vault_id = :vaultId ORDER BY name")
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> {
                var id = (UUID) rs.getObject("id");
                return new Group(id, vaultId, rs.getString("name"), membersByGroupId.getOrDefault(id, Set.of()));
            })
            .list();
    }

    @Override
    public Set<UUID> listRoleIdsForGroup(UUID groupId) {
        return Set.copyOf(jdbcClient.sql("SELECT role_id FROM platform.group_roles WHERE group_id = :groupId")
            .param("groupId", groupId)
            .query(UUID.class)
            .list());
    }

    @Override
    public Set<VaultId> listAccessibleVaultIds(String subject) {
        var ids = jdbcClient.sql("""
                SELECT DISTINCT g.vault_id FROM platform.group_members gm
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
                FROM platform.group_members gm
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

    private static final RowMapper<PathRule> PATH_RULE_MAPPER = (rs, rowNum) -> new PathRule(
        rs.getString("path_prefix"),
        scopeOf(rs.getString("scope_type"), rs.getString("scope_subject")),
        RuleEffect.valueOf(rs.getString("effect"))
    );

    private static final RowMapper<TopicRule> TOPIC_RULE_MAPPER = (rs, rowNum) -> new TopicRule(
        rs.getString("topic"),
        scopeOf(rs.getString("scope_type"), rs.getString("scope_subject")),
        RuleEffect.valueOf(rs.getString("effect"))
    );

    @Override
    public PathRule createPathRule(VaultId vaultId, String pathPrefix, RuleScope scope, RuleEffect effect) {
        jdbcClient.sql("""
                INSERT INTO platform.path_rules (vault_id, path_prefix, scope_type, scope_subject, effect)
                VALUES (:vaultId, :pathPrefix, :scopeType, :scopeSubject, :effect)
                """)
            .param("vaultId", vaultId.value())
            .param("pathPrefix", pathPrefix)
            .param("scopeType", scopeTypeOf(scope))
            .param("scopeSubject", scopeSubjectOf(scope))
            .param("effect", effect.name())
            .update();
        return new PathRule(pathPrefix, scope, effect);
    }

    @Override
    public List<PathRule> listPathRules(VaultId vaultId) {
        return jdbcClient.sql("SELECT * FROM platform.path_rules WHERE vault_id = :vaultId ORDER BY id")
            .param("vaultId", vaultId.value())
            .query(PATH_RULE_MAPPER)
            .list();
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
}
