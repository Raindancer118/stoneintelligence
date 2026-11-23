package de.tstieh.stoneintelligence.platform.sync.relay;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Zwei Instanzen an einer echten Datenbank (ADR 0013, Schritt 7). */
@Testcontainers
class PostgresClusterBusIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static SimpleDriverDataSource dataSource() {
        return new SimpleDriverDataSource(new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword());
    }

    @Test
    void should_deliverToTheOtherInstance_butNotBackToTheSender() throws Exception {
        try (var first = new PostgresClusterBus(dataSource()); var second = new PostgresClusterBus(dataSource())) {
            BlockingQueue<ClusterMessage> atFirst = new LinkedBlockingQueue<>();
            BlockingQueue<ClusterMessage> atSecond = new LinkedBlockingQueue<>();
            first.subscribe(atFirst::add);
            second.subscribe(atSecond::add);
            var note = NoteId.newId();
            var event = new ClusterMessage.VaultEvent(VaultId.newId(), SyncFrame.TYPE_VAULT_NOTE_CREATED, note, NoteKind.NOTE,
                "Ordner/Neue Notiz – ä.md");

            // LISTEN laeuft auf einem eigenen Thread an; so lange wiederholen, bis es ankommt.
            ClusterMessage received = null;
            var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (received == null && System.nanoTime() < deadline) {
                first.publish(event);
                received = atSecond.poll(500, TimeUnit.MILLISECONDS);
            }

            assertThat(received).usingRecursiveComparison().isEqualTo(event);
            assertThat(atFirst).isEmpty();
        }
    }

    @Test
    void should_dropOversizedMessages_insteadOfFailingTheCaller() {
        try (var bus = new PostgresClusterBus(dataSource())) {
            var huge = new ClusterMessage.Awareness(NoteId.newId(), "x/1", new byte[PostgresClusterBus.MAX_PAYLOAD_BYTES]);

            bus.publish(huge);
        }
    }
}
