package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifiziert {@link JdbcTicketStore} gegen echtes Postgres - insbesondere, dass
 * {@code takeIfValid} atomar ist (DELETE...RETURNING), damit Mehr-Instanz-Betrieb kein
 * Doppel-Einloesen desselben Tickets zulaesst. Braucht einen laufenden Docker-Daemon.
 */
@Testcontainers
class JdbcTicketStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static JdbcTicketStore store;

    @BeforeAll
    static void migrateAndBuildStore() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform")
            .locations("classpath:db/migration/platform")
            .load()
            .migrate();

        DataSource dataSource = new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        store = new JdbcTicketStore(JdbcClient.create(dataSource));
    }

    @BeforeEach
    void cleanTable() {
        DataSource dataSource = new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcClient.create(dataSource).sql("TRUNCATE platform.sync_tickets").update();
    }

    @Test
    void should_returnPutTicket_when_takenBeforeExpiry() {
        var ticket = new SyncTicket(
            "token-1", VaultId.newId(), NoteId.newId(), "tom", Instant.now(), Instant.now().plusSeconds(30));
        store.put(ticket);

        var taken = store.takeIfValid("token-1", Instant.now());

        // Postgres' timestamptz speichert nur Mikrosekunden-Aufloesung und RUNDET dabei (nicht
        // nur Abschneiden) - ein exakter Objektvergleich wuerde am unvermeidlichen
        // Rundungsverlust beim Schreiben/Lesen ueber die DB scheitern, deshalb Toleranz statt
        // exakter Gleichheit fuer die Zeitstempel.
        assertThat(taken).isPresent();
        assertThat(taken.get().token()).isEqualTo(ticket.token());
        assertThat(taken.get().vaultId()).isEqualTo(ticket.vaultId());
        assertThat(taken.get().noteId()).isEqualTo(ticket.noteId());
        assertThat(taken.get().actor()).isEqualTo(ticket.actor());
        assertThat(taken.get().issuedAt()).isCloseTo(ticket.issuedAt(), within(1, ChronoUnit.MICROS));
        assertThat(taken.get().expiresAt()).isCloseTo(ticket.expiresAt(), within(1, ChronoUnit.MICROS));
    }

    @Test
    void should_returnEmpty_when_takenTwice() {
        var ticket = new SyncTicket(
            "token-1", VaultId.newId(), NoteId.newId(), "tom", Instant.now(), Instant.now().plusSeconds(30));
        store.put(ticket);

        store.takeIfValid("token-1", Instant.now());
        var secondAttempt = store.takeIfValid("token-1", Instant.now());

        assertThat(secondAttempt).isEmpty();
    }

    @Test
    void should_returnEmpty_when_ticketExpired() {
        var ticket = new SyncTicket(
            "token-1", VaultId.newId(), NoteId.newId(), "tom",
            Instant.now().minusSeconds(60), Instant.now().minusSeconds(30));
        store.put(ticket);

        assertThat(store.takeIfValid("token-1", Instant.now())).isEmpty();
    }
}
