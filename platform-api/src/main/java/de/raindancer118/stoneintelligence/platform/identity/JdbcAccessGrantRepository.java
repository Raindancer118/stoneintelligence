package de.raindancer118.stoneintelligence.platform.identity;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAccessGrantRepository implements AccessGrantRepository {

    private final JdbcClient jdbcClient;

    public JdbcAccessGrantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<AccessGrant> list(VaultId vaultId) {
        return jdbcClient.sql("""
                SELECT g.id, g.folder_path, g.note_id, n.path AS note_path, g.scope_type, g.scope_subject, g.permissions
                FROM platform.access_grants g
                LEFT JOIN platform.notes n ON n.id = g.note_id
                WHERE g.vault_id = :vaultId
                ORDER BY g.created_at, g.id
                """)
            .param("vaultId", vaultId.value())
            .query((rs, rowNum) -> map(rs, vaultId))
            .list();
    }

    @Override
    @Transactional
    public AccessGrant put(VaultId vaultId, GrantTarget target, GrantScope scope, Set<Permission> permissions, String actor) {
        remove(vaultId, target, scope);
        var id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO platform.access_grants (id, vault_id, folder_path, note_id, scope_type, scope_subject, permissions, created_by)
                VALUES (:id, :vaultId, :folderPath, :noteId, :scopeType, :scopeSubject, CAST(:permissions AS text[]), :actor)
                """)
            .param("id", id)
            .param("vaultId", vaultId.value())
            .param("folderPath", target instanceof GrantTarget.Folder folder ? folder.path() : null)
            .param("noteId", target instanceof GrantTarget.Entry entry ? entry.noteId().value() : null)
            .param("scopeType", scope.type())
            .param("scopeSubject", scope.subject())
            .param("permissions", permissions == null ? null : arrayLiteral(permissions))
            .param("actor", actor)
            .update();
        return new AccessGrant(id, vaultId, target, scope, permissions);
    }

    @Override
    public boolean remove(VaultId vaultId, GrantTarget target, GrantScope scope) {
        var targetCondition = target instanceof GrantTarget.Folder ? "folder_path = :folderPath" : "note_id = :noteId";
        return jdbcClient.sql("""
                DELETE FROM platform.access_grants
                WHERE vault_id = :vaultId AND %s AND scope_type = :scopeType
                  AND coalesce(scope_subject, '') = coalesce(CAST(:scopeSubject AS text), '')
                """.formatted(targetCondition))
            .param("vaultId", vaultId.value())
            .param("folderPath", target instanceof GrantTarget.Folder folder ? folder.path() : null)
            .param("noteId", target instanceof GrantTarget.Entry entry ? entry.noteId().value() : null)
            .param("scopeType", scope.type())
            .param("scopeSubject", scope.subject())
            .update() > 0;
    }

    @Override
    public void moveFolder(VaultId vaultId, String from, String to) {
        var source = AccessResolver.normalize(from);
        jdbcClient.sql("""
                UPDATE platform.access_grants
                SET folder_path = :to || substr(folder_path, length(:from) + 1)
                WHERE vault_id = :vaultId AND (folder_path = :from OR starts_with(folder_path, :from || '/'))
                """)
            .param("vaultId", vaultId.value())
            .param("from", source)
            .param("to", AccessResolver.normalize(to))
            .update();
    }

    @Override
    public void removeFolder(VaultId vaultId, String folder) {
        jdbcClient.sql("""
                DELETE FROM platform.access_grants
                WHERE vault_id = :vaultId AND (folder_path = :folder OR starts_with(folder_path, :folder || '/'))
                """)
            .param("vaultId", vaultId.value())
            .param("folder", AccessResolver.normalize(folder))
            .update();
    }

    @Override
    public void removeSubject(VaultId vaultId, String subject) {
        removeScope(vaultId, GrantScope.user(subject));
    }

    @Override
    public void removeGroup(VaultId vaultId, UUID groupId) {
        removeScope(vaultId, GrantScope.group(groupId));
    }

    private void removeScope(VaultId vaultId, GrantScope scope) {
        jdbcClient.sql("DELETE FROM platform.access_grants WHERE vault_id = :vaultId AND scope_type = :type AND scope_subject = :subject")
            .param("vaultId", vaultId.value())
            .param("type", scope.type())
            .param("subject", scope.subject())
            .update();
    }

    private static AccessGrant map(ResultSet rs, VaultId vaultId) throws SQLException {
        var noteId = (UUID) rs.getObject("note_id");
        var target = noteId == null
            ? GrantTarget.folder(rs.getString("folder_path"))
            : GrantTarget.entry(NoteId.of(noteId), rs.getString("note_path"));
        return new AccessGrant((UUID) rs.getObject("id"), vaultId, target,
            GrantScope.of(rs.getString("scope_type"), rs.getString("scope_subject")), permissionsOf(rs.getArray("permissions")));
    }

    private static Set<Permission> permissionsOf(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        var result = EnumSet.noneOf(Permission.class);
        for (var name : (String[]) array.getArray()) {
            result.add(Permission.valueOf(name));
        }
        return result;
    }

    /** Postgres-Array-Literal, z. B. {@code {READ,WRITE}} - Permission-Namen brauchen kein Quoting. */
    private static String arrayLiteral(Set<Permission> permissions) {
        return "{" + String.join(",", permissions.stream().map(Enum::name).sorted().toList()) + "}";
    }
}
