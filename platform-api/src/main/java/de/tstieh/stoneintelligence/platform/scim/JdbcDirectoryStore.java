package de.tstieh.stoneintelligence.platform.scim;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.platform.scim.ScimFilter.Compare;
import de.tstieh.stoneintelligence.platform.scim.ScimFilter.ValuePath;
import de.tstieh.stoneintelligence.platform.scim.ScimSql.Column;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDirectoryStore implements DirectoryStore {

    private static final Map<String, Column> ACCOUNT_COLUMNS = Map.of(
        "id", Column.text("id::text", true),
        "username", Column.text("user_name", false),
        "externalid", Column.text("external_id", true),
        "displayname", Column.text("display_name", false),
        "active", Column.bool("active"),
        "meta.created", Column.text("to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS')", true),
        "meta.lastmodified", Column.text("to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS')", true));

    private static final Map<String, Column> GROUP_COLUMNS = Map.of(
        "id", Column.text("g.id::text", true),
        "displayname", Column.text("g.display_name", false),
        "externalid", Column.text("g.external_id", true));

    private static final String ACCOUNT = "id, subject, user_name, external_id, display_name, active, created_at, updated_at";
    private static final String GROUP = "g.id, g.idp_key, g.display_name, g.external_id, g.created_at, g.updated_at";

    private final JdbcClient jdbcClient;

    public JdbcDirectoryStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public Account saveAccount(UUID id, AccountData data) {
        var target = id;
        if (target == null) {
            target = jdbcClient.sql("""
                    SELECT id FROM platform.directory_users WHERE lower(user_name) = lower(:userName) AND deleted_at IS NOT NULL
                    """)
                .param("userName", data.userName()).query(UUID.class).optional().orElse(null);
        } else if (account(target).isEmpty()) {
            throw ScimException.notFound("User");
        }
        try {
            if (target == null) {
                return jdbcClient.sql("""
                        INSERT INTO platform.directory_users (subject, user_name, external_id, display_name, active)
                        VALUES (:subject, :userName, :externalId, :displayName, :active)
                        RETURNING %s
                        """.formatted(ACCOUNT))
                    .params(params(data)).query(JdbcDirectoryStore::account).single();
            }
            return jdbcClient.sql("""
                    UPDATE platform.directory_users SET subject = :subject, user_name = :userName, external_id = :externalId,
                           display_name = :displayName, active = :active, deleted_at = NULL, updated_at = now()
                     WHERE id = :id
                    RETURNING %s
                    """.formatted(ACCOUNT))
                .params(params(data)).param("id", target).query(JdbcDirectoryStore::account).single();
        } catch (DuplicateKeyException duplicate) {
            throw ScimException.uniqueness("userName or the sign-in subject is already taken by another account");
        }
    }

    private static Map<String, Object> params(AccountData data) {
        var params = new HashMap<String, Object>();
        params.put("subject", data.subject());
        params.put("userName", data.userName());
        params.put("externalId", data.externalId());
        params.put("displayName", data.displayName());
        params.put("active", data.active());
        return params;
    }

    @Override
    public Optional<Account> account(UUID id) {
        return jdbcClient.sql("SELECT " + ACCOUNT + " FROM platform.directory_users WHERE id = :id AND deleted_at IS NULL")
            .param("id", id).query(JdbcDirectoryStore::account).optional();
    }

    @Override
    public Page<Account> accounts(ScimFilter filter, int startIndex, int count) {
        var params = new HashMap<String, Object>();
        var where = "deleted_at IS NULL" + (filter == null ? ""
            : " AND " + new ScimSql(ACCOUNT_COLUMNS, (path, sql) -> null, params).where(filter.expression()));
        var total = jdbcClient.sql("SELECT count(*) FROM platform.directory_users WHERE " + where)
            .params(params).query(Integer.class).single();
        var items = count <= 0 ? List.<Account>of() : jdbcClient.sql(
                "SELECT " + ACCOUNT + " FROM platform.directory_users WHERE " + where
                    + " ORDER BY created_at, id LIMIT :limit OFFSET :offset")
            .params(params).param("limit", count).param("offset", Math.max(startIndex, 1) - 1)
            .query(JdbcDirectoryStore::account).list();
        return new Page<>(items, total);
    }

    @Override
    @Transactional
    public Optional<Account> deleteAccount(UUID id) {
        var account = account(id);
        if (account.isPresent()) {
            jdbcClient.sql("DELETE FROM platform.directory_group_members WHERE user_id = :id").param("id", id).update();
            jdbcClient.sql("""
                    UPDATE platform.directory_users SET active = false, deleted_at = now(), updated_at = now(),
                           display_name = NULL, external_id = NULL
                     WHERE id = :id
                    """)
                .param("id", id).update();
        }
        return account;
    }

    @Override
    public int purgeDeletedBefore(java.time.Instant before) {
        return jdbcClient.sql("DELETE FROM platform.directory_users WHERE deleted_at < :before")
            .param("before", java.sql.Timestamp.from(before)).update();
    }

    @Override
    public Group saveGroup(UUID id, GroupData data) {
        try {
            if (id == null) {
                return jdbcClient.sql("""
                        INSERT INTO platform.directory_groups AS g (idp_key, display_name, external_id)
                        VALUES (:key, :displayName, :externalId) RETURNING %s
                        """.formatted(GROUP))
                    .param("key", data.idpKey()).param("displayName", data.displayName()).param("externalId", data.externalId())
                    .query(JdbcDirectoryStore::group).single();
            }
            return jdbcClient.sql("""
                    UPDATE platform.directory_groups AS g SET idp_key = :key, display_name = :displayName,
                           external_id = :externalId, updated_at = now()
                     WHERE id = :id RETURNING %s
                    """.formatted(GROUP))
                .param("id", id).param("key", data.idpKey()).param("displayName", data.displayName())
                .param("externalId", data.externalId())
                .query(JdbcDirectoryStore::group).optional()
                .orElseThrow(() -> ScimException.notFound("Group"));
        } catch (DuplicateKeyException duplicate) {
            throw ScimException.uniqueness("group '" + data.idpKey() + "' already exists");
        }
    }

    @Override
    public Optional<Group> group(UUID id) {
        return jdbcClient.sql("SELECT " + GROUP + " FROM platform.directory_groups g WHERE g.id = :id")
            .param("id", id).query(JdbcDirectoryStore::group).optional();
    }

    @Override
    public Page<Group> groups(ScimFilter filter, int startIndex, int count) {
        var params = new HashMap<String, Object>();
        var where = filter == null ? "true"
            : new ScimSql(GROUP_COLUMNS, JdbcDirectoryStore::memberFilter, params).where(rewriteMemberValue(filter.expression()));
        var total = jdbcClient.sql("SELECT count(*) FROM platform.directory_groups g WHERE " + where)
            .params(params).query(Integer.class).single();
        var items = count <= 0 ? List.<Group>of() : jdbcClient.sql(
                "SELECT " + GROUP + " FROM platform.directory_groups g WHERE " + where
                    + " ORDER BY g.created_at, g.id LIMIT :limit OFFSET :offset")
            .params(params).param("limit", count).param("offset", Math.max(startIndex, 1) - 1)
            .query(JdbcDirectoryStore::group).list();
        return new Page<>(items, total);
    }

    /** {@code members.value eq "x"} ist dasselbe wie {@code members[value eq "x"]}. */
    private static ScimFilter.Expression rewriteMemberValue(ScimFilter.Expression expression) {
        return switch (expression) {
            case ScimFilter.And and -> new ScimFilter.And(rewriteMemberValue(and.left()), rewriteMemberValue(and.right()));
            case ScimFilter.Or or -> new ScimFilter.Or(rewriteMemberValue(or.left()), rewriteMemberValue(or.right()));
            case ScimFilter.Not not -> new ScimFilter.Not(rewriteMemberValue(not.inner()));
            case Compare compare when compare.attribute().equalsIgnoreCase("members.value") ->
                new ValuePath("members", new Compare("value", compare.op(), compare.value()));
            default -> expression;
        };
    }

    /** Nur {@code members[value eq "<id>"]} - so fragen IdPs, ob ein Konto in einer Gruppe ist. */
    private static String memberFilter(ValuePath path, ScimSql sql) {
        if (!path.attribute().equalsIgnoreCase("members")
                || !(path.filter() instanceof Compare compare)
                || !compare.attribute().equalsIgnoreCase("value")
                || compare.op() != ScimFilter.Op.EQ
                || compare.value() == null) {
            return null;
        }
        UUID member;
        try {
            member = UUID.fromString(String.valueOf(compare.value()));
        } catch (IllegalArgumentException notAnId) {
            return "false";
        }
        return "EXISTS (SELECT 1 FROM platform.directory_group_members m WHERE m.group_id = g.id AND m.user_id = "
            + sql.bind(member) + ")";
    }

    @Override
    public List<Member> members(UUID groupId) {
        return jdbcClient.sql("""
                SELECT u.id, u.display_name FROM platform.directory_group_members m
                  JOIN platform.directory_users u ON u.id = m.user_id
                 WHERE m.group_id = :groupId ORDER BY u.user_name
                """)
            .param("groupId", groupId)
            .query((rs, rowNum) -> new Member((UUID) rs.getObject("id"), rs.getString("display_name")))
            .list();
    }

    @Override
    @Transactional
    public void replaceMembers(UUID groupId, Set<UUID> accountIds) {
        var ids = accountIds.toArray(UUID[]::new);
        // Nur die Differenz schreiben: jede Zeile loest Trigger aus (Rechte-Cache, Feed je Person).
        jdbcClient.sql("DELETE FROM platform.directory_group_members WHERE group_id = :groupId AND NOT (user_id = ANY (:ids))")
            .param("groupId", groupId).param("ids", ids).update();
        addMembers(groupId, accountIds);
    }

    @Override
    public void addMembers(UUID groupId, Set<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                INSERT INTO platform.directory_group_members (group_id, user_id)
                SELECT :groupId, u.id FROM platform.directory_users u
                 WHERE u.id = ANY (:ids) AND u.deleted_at IS NULL
                   AND EXISTS (SELECT 1 FROM platform.directory_groups WHERE id = :groupId)
                ON CONFLICT DO NOTHING
                """)
            .param("groupId", groupId).param("ids", accountIds.toArray(UUID[]::new)).update();
    }

    @Override
    public void removeMembers(UUID groupId, Set<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("DELETE FROM platform.directory_group_members WHERE group_id = :groupId AND user_id = ANY (:ids)")
            .param("groupId", groupId).param("ids", accountIds.toArray(UUID[]::new)).update();
    }

    /** Erst die Mitglieder, damit ihre Trigger noch sehen, welche Gruppe sie verlassen. */
    @Override
    @Transactional
    public boolean deleteGroup(UUID id) {
        jdbcClient.sql("DELETE FROM platform.directory_group_members WHERE group_id = :id").param("id", id).update();
        return jdbcClient.sql("DELETE FROM platform.directory_groups WHERE id = :id").param("id", id).update() > 0;
    }

    @Override
    public Set<String> idpKeysOf(UUID accountId) {
        return Set.copyOf(jdbcClient.sql("""
                SELECT g.idp_key FROM platform.directory_group_members m JOIN platform.directory_groups g ON g.id = m.group_id
                 WHERE m.user_id = :id
                """)
            .param("id", accountId).query(String.class).list());
    }

    @Override
    public Set<String> blockedSubjects() {
        return Set.copyOf(jdbcClient.sql("SELECT subject FROM platform.blocked_subjects").query(String.class).list());
    }

    private static Account account(ResultSet rs, int rowNum) throws SQLException {
        return new Account((UUID) rs.getObject("id"), rs.getString("subject"), rs.getString("user_name"),
            rs.getString("external_id"), rs.getString("display_name"), rs.getBoolean("active"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static Group group(ResultSet rs, int rowNum) throws SQLException {
        return new Group((UUID) rs.getObject("id"), rs.getString("idp_key"), rs.getString("display_name"),
            rs.getString("external_id"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
