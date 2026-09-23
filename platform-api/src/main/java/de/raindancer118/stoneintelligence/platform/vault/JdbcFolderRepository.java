package de.raindancer118.stoneintelligence.platform.vault;

import java.util.ArrayList;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcFolderRepository implements FolderRepository {

    /** Ordner selbst oder darunter - bewusst ohne LIKE, damit {@code _} und {@code %} im Namen nichts bedeuten. */
    private static final String INSIDE = "(path = :path OR left(path, length(:path) + 1) = :path || '/')";

    private final JdbcClient jdbcClient;

    public JdbcFolderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<String> list(VaultId vaultId) {
        return jdbcClient.sql("SELECT path FROM platform.folders WHERE vault_id = :vaultId ORDER BY path COLLATE \"C\"")
            .param("vaultId", vaultId.value())
            .query(String.class)
            .list();
    }

    @Override
    public List<String> ensure(VaultId vaultId, String path, String actor) {
        var created = new ArrayList<String>();
        for (var folder : FolderPaths.withParents(path)) {
            var inserted = jdbcClient.sql("""
                    INSERT INTO platform.folders (vault_id, path, created_by) VALUES (:vaultId, :path, :actor)
                    ON CONFLICT DO NOTHING
                    """)
                .param("vaultId", vaultId.value())
                .param("path", folder)
                .param("actor", actor)
                .update();
            if (inserted == 1) {
                created.add(folder);
            }
        }
        return created;
    }

    @Override
    public java.util.Optional<String> creator(VaultId vaultId, String path) {
        return jdbcClient.sql("SELECT created_by FROM platform.folders WHERE vault_id = :vaultId AND path = :path")
            .param("vaultId", vaultId.value())
            .param("path", path)
            .query(String.class)
            .optional();
    }

    @Override
    public int deleteTree(VaultId vaultId, String path) {
        return jdbcClient.sql("DELETE FROM platform.folders WHERE vault_id = :vaultId AND " + INSIDE)
            .param("vaultId", vaultId.value())
            .param("path", path)
            .update();
    }

    @Override
    @Transactional
    public void renameTree(VaultId vaultId, String from, String to, String actor) {
        var inside = jdbcClient.sql("SELECT count(*) FROM platform.folders WHERE vault_id = :vaultId AND " + INSIDE)
            .param("vaultId", vaultId.value())
            .param("path", from)
            .query(Long.class)
            .single();
        if (inside == 0) {
            return;
        }
        ensure(vaultId, to, actor);
        jdbcClient.sql("""
                INSERT INTO platform.folders (vault_id, path, created_by, created_at)
                SELECT vault_id, :to || substr(path, length(:path) + 1), created_by, created_at
                FROM platform.folders WHERE vault_id = :vaultId AND %s
                ON CONFLICT DO NOTHING
                """.formatted(INSIDE))
            .param("vaultId", vaultId.value())
            .param("path", from)
            .param("to", to)
            .update();
        jdbcClient.sql("DELETE FROM platform.folders WHERE vault_id = :vaultId AND " + INSIDE
                + " AND NOT (path = :to OR left(path, length(:to) + 1) = :to || '/')")
            .param("vaultId", vaultId.value())
            .param("path", from)
            .param("to", to)
            .update();
    }
}
