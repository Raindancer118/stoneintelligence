package de.tstieh.stoneintelligence.platform.sync.ticket;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTicketStore implements TicketStore {

    private static final RowMapper<SyncTicket> TICKET_MAPPER = (rs, rowNum) -> new SyncTicket(
        rs.getString("token"),
        VaultId.of(rs.getString("vault_id")),
        rs.getString("actor"),
        rs.getTimestamp("issued_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant()
    );

    private final JdbcClient jdbcClient;

    public JdbcTicketStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void put(SyncTicket ticket) {
        jdbcClient.sql("""
                INSERT INTO platform.sync_tickets (token, vault_id, actor, issued_at, expires_at)
                VALUES (:token, :vaultId, :actor, :issuedAt, :expiresAt)
                """)
            .param("token", ticket.token())
            .param("vaultId", ticket.vaultId().value())
            .param("actor", ticket.actor())
            // Postgres' JDBC-Treiber kann den SQL-Typ fuer ein rohes java.time.Instant nicht
            // ableiten (setObject() schlaegt fehl) - explizit auf java.sql.Timestamp abbilden.
            .param("issuedAt", Timestamp.from(ticket.issuedAt()))
            .param("expiresAt", Timestamp.from(ticket.expiresAt()))
            .update();

        // Guenstige, beilaeufige Bereinigung abgelaufener, nie eingeloester Tickets statt eines
        // eigenen Scheduled-Jobs - Tickets leben Sekunden, die Tabelle bleibt so von selbst klein.
        jdbcClient.sql("DELETE FROM platform.sync_tickets WHERE expires_at < :now")
            .param("now", Timestamp.from(Instant.now()))
            .update();
    }

    /** Atomar via DELETE ... RETURNING - kein Race zwischen zwei gleichzeitigen Einloeseversuchen. */
    @Override
    public Optional<SyncTicket> takeIfValid(String token, Instant now) {
        var ticket = jdbcClient.sql("""
                DELETE FROM platform.sync_tickets WHERE token = :token AND expires_at >= :now
                RETURNING token, vault_id, actor, issued_at, expires_at
                """)
            .param("token", token)
            .param("now", Timestamp.from(now))
            .query(TICKET_MAPPER)
            .optional();

        if (ticket.isPresent()) {
            return ticket;
        }
        // Token existierte evtl., war aber abgelaufen - trotzdem loeschen, damit es nicht
        // liegen bleibt (das obige DELETE greift dank der expires_at-Bedingung dafuer nicht).
        jdbcClient.sql("DELETE FROM platform.sync_tickets WHERE token = :token")
            .param("token", token)
            .update();
        return Optional.empty();
    }
}
