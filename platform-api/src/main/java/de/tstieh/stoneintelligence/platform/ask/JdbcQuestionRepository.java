package de.tstieh.stoneintelligence.platform.ask;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuestionRepository implements QuestionRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = """
        id, vault_id, asked_by, service, question, job_id, asked_at, answered_at, answered, answer, sources, cited, omitted_for_consent""";

    private record StoredSource(int n, String noteId, String heading, String excerpt) { }

    private final JdbcClient jdbc;

    public JdbcQuestionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Question map(ResultSet rs, int row) throws SQLException {
        try {
            var answeredAt = rs.getTimestamp("answered_at");
            var sources = JSON.readValue(rs.getString("sources"), new TypeReference<List<StoredSource>>() { }).stream()
                .map(s -> new Question.Source(s.n(), NoteId.of(s.noteId()), s.heading(), s.excerpt())).toList();
            return new Question(UUID.fromString(rs.getString("id")), VaultId.of(rs.getString("vault_id")), rs.getString("asked_by"),
                rs.getString("service"), rs.getString("question"), UUID.fromString(rs.getString("job_id")),
                rs.getTimestamp("asked_at").toInstant(), answeredAt == null ? null : answeredAt.toInstant(),
                rs.getObject("answered", Boolean.class), rs.getString("answer"), sources,
                JSON.readValue(rs.getString("cited"), new TypeReference<List<Integer>>() { }), rs.getInt("omitted_for_consent"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt question " + rs.getString("id"), e);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Question create(Question question) {
        jdbc.sql("""
                INSERT INTO platform.vault_questions (id, vault_id, asked_by, service, question, job_id, asked_at)
                VALUES (:id, :vault, :askedBy, :service, :question, :job, :at)
                """)
            .param("id", question.id()).param("vault", question.vaultId().value()).param("askedBy", question.askedBy())
            .param("service", question.service()).param("question", question.question()).param("job", question.jobId())
            .param("at", Timestamp.from(question.askedAt()))
            .update();
        return question;
    }

    @Override
    public Optional<Question> find(VaultId vaultId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM platform.vault_questions WHERE id = :id AND vault_id = :vault")
            .param("id", id).param("vault", vaultId.value()).query(JdbcQuestionRepository::map).optional();
    }

    @Override
    public Optional<Question> findByJob(UUID jobId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM platform.vault_questions WHERE job_id = :job")
            .param("job", jobId).query(JdbcQuestionRepository::map).optional();
    }

    @Override
    public List<Question> listBy(VaultId vaultId, String askedBy, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM platform.vault_questions WHERE vault_id = :vault AND asked_by = :askedBy ORDER BY asked_at DESC LIMIT :limit
                """)
            .param("vault", vaultId.value()).param("askedBy", askedBy).param("limit", limit)
            .query(JdbcQuestionRepository::map).list();
    }

    @Override
    public void saveSources(UUID id, List<Question.Source> sources, int omittedForConsent) {
        var stored = sources.stream().map(s -> new StoredSource(s.n(), s.noteId().value().toString(), s.heading(), s.excerpt())).toList();
        jdbc.sql("UPDATE platform.vault_questions SET sources = CAST(:sources AS jsonb), omitted_for_consent = :omitted WHERE id = :id")
            .param("sources", json(stored)).param("omitted", omittedForConsent).param("id", id).update();
    }

    @Override
    public void saveAnswer(UUID id, boolean answered, String answer, List<Integer> cited, Instant at) {
        jdbc.sql("""
                UPDATE platform.vault_questions SET answered = :answered, answer = :answer, cited = CAST(:cited AS jsonb), answered_at = :at
                WHERE id = :id
                """)
            .param("answered", answered).param("answer", answer).param("cited", json(cited)).param("at", Timestamp.from(at))
            .param("id", id).update();
    }

    @Override
    public boolean delete(VaultId vaultId, String askedBy, UUID id) {
        return jdbc.sql("DELETE FROM platform.vault_questions WHERE id = :id AND vault_id = :vault AND asked_by = :askedBy")
            .param("id", id).param("vault", vaultId.value()).param("askedBy", askedBy).update() == 1;
    }

    @Override
    public int purgeAskedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM platform.vault_questions WHERE asked_at < :cutoff").param("cutoff", Timestamp.from(cutoff)).update();
    }
}
