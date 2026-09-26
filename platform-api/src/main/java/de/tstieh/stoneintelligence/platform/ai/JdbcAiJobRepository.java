package de.tstieh.stoneintelligence.platform.ai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAiJobRepository implements AiJobRepository {

    /** Alles ausser dem Dokument - das wird nur gezielt geladen. */
    private static final String COLUMNS = """
        id, vault_id, service, requested_by, file_name, content_type, size, level, status, attempts, max_attempts,
        available_at, lease_until, progress, percent, error, change_set_id, created_at, finished_at, waiting_for_capacity, kind""";

    private final JdbcClient jdbcClient;

    public JdbcAiJobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static AiJob map(ResultSet rs, int rowNum) throws SQLException {
        var changeSet = rs.getString("change_set_id");
        var percent = rs.getObject("percent", Integer.class);
        return new AiJob(UUID.fromString(rs.getString("id")), VaultId.of(rs.getString("vault_id")), rs.getString("service"),
            rs.getString("requested_by"), rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size"),
            rs.getInt("level"), AiJob.Status.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getInt("max_attempts"),
            instant(rs, "available_at"), instant(rs, "lease_until"), rs.getString("progress"), percent, rs.getString("error"),
            changeSet == null ? null : UUID.fromString(changeSet), instant(rs, "created_at"), instant(rs, "finished_at"),
            rs.getBoolean("waiting_for_capacity"), AiJob.Kind.valueOf(rs.getString("kind")));
    }

    @Override
    public AiJob create(NewAiJob job, Instant at) {
        return jdbcClient.sql("""
                INSERT INTO platform.ai_jobs (id, vault_id, service, requested_by, file_name, content_type, size, level, content,
                    status, max_attempts, available_at, created_at, kind)
                VALUES (:id, :vaultId, :service, :requestedBy, :fileName, :contentType, :size, :level, :content,
                    'PENDING', :maxAttempts, :at, :at, :kind)
                RETURNING %s
                """.formatted(COLUMNS))
            .param("id", UUID.randomUUID())
            .param("vaultId", job.vaultId().value())
            .param("service", job.service())
            .param("requestedBy", job.requestedBy())
            .param("fileName", job.fileName())
            .param("contentType", job.contentType())
            .param("size", job.size())
            .param("kind", job.kind().name())
            .param("level", job.level())
            .param("content", job.content())
            .param("maxAttempts", job.maxAttempts())
            .param("at", Timestamp.from(at))
            .query(JdbcAiJobRepository::map)
            .single();
    }

    @Override
    public Optional<AiJob> find(VaultId vaultId, UUID id) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM platform.ai_jobs WHERE id = :id AND vault_id = :vaultId")
            .param("id", id)
            .param("vaultId", vaultId.value())
            .query(JdbcAiJobRepository::map)
            .optional();
    }

    @Override
    public Optional<AiJob> findById(UUID id) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM platform.ai_jobs WHERE id = :id")
            .param("id", id)
            .query(JdbcAiJobRepository::map)
            .optional();
    }

    @Override
    public List<AiJob> list(VaultId vaultId, int limit) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM platform.ai_jobs WHERE vault_id = :vaultId ORDER BY created_at DESC LIMIT :limit")
            .param("vaultId", vaultId.value())
            .param("limit", limit)
            .query(JdbcAiJobRepository::map)
            .list();
    }

    @Override
    public int countOpen(VaultId vaultId) {
        return jdbcClient.sql("SELECT count(*) FROM platform.ai_jobs WHERE vault_id = :vaultId AND status IN ('PENDING', 'RUNNING')")
            .param("vaultId", vaultId.value())
            .query(Integer.class)
            .single();
    }

    @Override
    public boolean hasOpen(VaultId vaultId, AiJob.Kind kind) {
        return jdbcClient.sql("""
                SELECT EXISTS (SELECT 1 FROM platform.ai_jobs
                               WHERE vault_id = :vaultId AND kind = :kind AND status IN ('PENDING', 'RUNNING'))
                """)
            .param("vaultId", vaultId.value())
            .param("kind", kind.name())
            .query(Boolean.class)
            .single();
    }

    @Override
    @Transactional
    public Optional<AiJob> claim(Instant now, Duration lease) {
        var at = Timestamp.from(now);
        jdbcClient.sql("""
                UPDATE platform.ai_jobs
                SET status = 'FAILED', content = NULL, finished_at = :now,
                    error = 'Abgebrochen: die Verarbeitung ist ' || attempts || '-mal nicht fertig geworden'
                WHERE status = 'RUNNING' AND lease_until < :now AND attempts >= max_attempts
                """)
            .param("now", at)
            .update();
        return jdbcClient.sql("""
                UPDATE platform.ai_jobs
                SET status = 'RUNNING', attempts = attempts + 1, lease_until = :leaseUntil, finished_at = NULL,
                    waiting_for_capacity = false
                WHERE id = (
                    SELECT id FROM platform.ai_jobs
                    WHERE attempts < max_attempts
                      AND ((status = 'PENDING' AND available_at <= :now) OR (status = 'RUNNING' AND lease_until < :now))
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED)
                RETURNING %s
                """.formatted(COLUMNS))
            .param("now", at)
            .param("leaseUntil", Timestamp.from(now.plus(lease)))
            .query(JdbcAiJobRepository::map)
            .optional();
    }

    @Override
    public Optional<byte[]> content(UUID id) {
        return jdbcClient.sql("SELECT content FROM platform.ai_jobs WHERE id = :id AND content IS NOT NULL")
            .param("id", id)
            .query((rs, row) -> rs.getBytes("content"))
            .optional();
    }

    @Override
    public void attachChangeSet(UUID id, UUID changeSetId) {
        jdbcClient.sql("UPDATE platform.ai_jobs SET change_set_id = :changeSetId WHERE id = :id")
            .param("id", id)
            .param("changeSetId", changeSetId)
            .update();
    }

    @Override
    public boolean progress(UUID id, String message, Integer percent, Instant leaseUntil) {
        return jdbcClient.sql("""
                UPDATE platform.ai_jobs
                SET progress = :message, percent = COALESCE(:percent, percent), lease_until = :leaseUntil
                WHERE id = :id AND status = 'RUNNING'
                """)
            .param("id", id)
            .param("message", message)
            .param("percent", percent)
            .param("leaseUntil", Timestamp.from(leaseUntil))
            .update() == 1;
    }

    @Override
    public boolean finish(UUID id, AiJob.Status status, String error, Instant at) {
        return jdbcClient.sql("""
                UPDATE platform.ai_jobs SET status = :status, error = :error, finished_at = :at, lease_until = NULL, content = NULL
                WHERE id = :id AND status = 'RUNNING'
                """)
            .param("id", id)
            .param("status", status.name())
            .param("error", error)
            .param("at", Timestamp.from(at))
            .update() == 1;
    }

    @Override
    public boolean retryLater(UUID id, String error, Instant availableAt) {
        return jdbcClient.sql("""
                UPDATE platform.ai_jobs
                SET status = 'PENDING', error = :error, available_at = :availableAt, lease_until = NULL, waiting_for_capacity = false
                WHERE id = :id AND status = 'RUNNING'
                """)
            .param("id", id)
            .param("error", error)
            .param("availableAt", Timestamp.from(availableAt))
            .update() == 1;
    }

    @Override
    public boolean waitForCapacity(UUID id, String error, Instant availableAt) {
        return jdbcClient.sql("""
                UPDATE platform.ai_jobs
                SET status = 'PENDING', error = :error, available_at = :availableAt, lease_until = NULL,
                    attempts = GREATEST(attempts - 1, 0), waiting_for_capacity = true
                WHERE id = :id AND status = 'RUNNING'
                """)
            .param("id", id)
            .param("error", error)
            .param("availableAt", Timestamp.from(availableAt))
            .update() == 1;
    }

    @Override
    public boolean cancel(VaultId vaultId, UUID id, Instant at) {
        return jdbcClient.sql("""
                UPDATE platform.ai_jobs
                SET status = 'CANCELLED', finished_at = :at, content = NULL, lease_until = NULL, waiting_for_capacity = false
                WHERE id = :id AND vault_id = :vaultId AND status IN ('PENDING', 'RUNNING')
                """)
            .param("id", id)
            .param("vaultId", vaultId.value())
            .param("at", Timestamp.from(at))
            .update() == 1;
    }

    @Override
    public int purgeCreatedBefore(Instant cutoff) {
        return jdbcClient.sql("DELETE FROM platform.ai_jobs WHERE created_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update();
    }
}
