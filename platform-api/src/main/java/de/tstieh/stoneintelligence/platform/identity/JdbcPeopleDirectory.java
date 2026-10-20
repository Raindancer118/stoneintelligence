package de.tstieh.stoneintelligence.platform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPeopleDirectory implements PeopleDirectory {

    private final JdbcClient jdbcClient;

    public JdbcPeopleDirectory(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void remember(String subject, String displayName, Instant seenAt) {
        jdbcClient.sql("""
                INSERT INTO platform.people (subject, display_name, last_seen_at) VALUES (:subject, :name, :seenAt)
                ON CONFLICT (subject) DO UPDATE SET display_name = EXCLUDED.display_name,
                    last_seen_at = GREATEST(platform.people.last_seen_at, EXCLUDED.last_seen_at)
                """)
            .param("subject", subject)
            .param("name", displayName)
            .param("seenAt", Timestamp.from(seenAt))
            .update();
    }

    @Override
    public Map<String, String> displayNames(Collection<String> subjects) {
        var names = new HashMap<String, String>();
        if (subjects.isEmpty()) {
            return names;
        }
        jdbcClient.sql("""
                SELECT s.subject, COALESCE(d.display_name, p.display_name) AS name
                  FROM unnest(:subjects) AS s(subject)
                  LEFT JOIN platform.directory_users d ON d.subject = s.subject AND d.deleted_at IS NULL
                  LEFT JOIN platform.people p ON p.subject = s.subject
                 WHERE COALESCE(d.display_name, p.display_name) IS NOT NULL
                """)
            .param("subjects", subjects.toArray(String[]::new))
            .query((rs, rowNum) -> names.put(rs.getString("subject"), rs.getString("name")))
            .list();
        return names;
    }

    @Override
    public int forgetNotSeenSince(Instant before) {
        return jdbcClient.sql("DELETE FROM platform.people WHERE last_seen_at < :before")
            .param("before", Timestamp.from(before))
            .update();
    }
}
