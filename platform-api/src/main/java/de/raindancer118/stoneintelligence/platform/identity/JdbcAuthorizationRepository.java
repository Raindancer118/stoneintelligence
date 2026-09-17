package de.raindancer118.stoneintelligence.platform.identity;

import java.util.LinkedHashSet;
import java.util.List;
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
    public void assignRole(UUID groupId, UUID roleId) {
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
        return jdbcClient.sql("SELECT * FROM platform.path_rules WHERE vault_id = :vaultId")
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
        return jdbcClient.sql("SELECT * FROM platform.topic_rules WHERE vault_id = :vaultId")
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
