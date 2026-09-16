package de.raindancer118.stoneintelligence.platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prüft die V1-Migration gegen einen echten Postgres (Testcontainers) und - wichtiger - dass die
 * Cascade-Delete-Policy aus V1__initial_schema.sql tatsächlich das tut, was Plan.md Abschnitt 3,
 * Fehlerklasse 4 verlangt: technisch abhängige Kind-Daten verschwinden mit dem Vault, Audit-
 * Events überleben ihn (Zeilen bleiben stehen, auch wenn vault_id danach ins Leere zeigt).
 *
 * <p>Braucht einen laufenden Docker-Daemon (lokal oder in CI) - ohne Docker wird dieser Test
 * mit einer eindeutigen Fehlermeldung von Testcontainers übersprungen/fehlschlagen, nicht still
 * grün werden.
 */
@Testcontainers
class PlatformSchemaMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform")
            .locations("classpath:db/migration/platform")
            .load()
            .migrate();
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Nested
    class CascadeDeletePolicy {

        @Test
        void should_cascadeDeleteNotesSnapshotsAndTombstones_butKeepAuditEvents_when_vaultIsDeleted() throws Exception {
            var vaultId = UUID.randomUUID();
            var noteId = UUID.randomUUID();

            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO platform.vaults (id, name) VALUES ('%s', 'test-vault')"
                    .formatted(vaultId));
                statement.execute(("INSERT INTO platform.notes (id, vault_id, path, note_level, created_by) "
                    + "VALUES ('%s', '%s', 'foo.md', 1, 'tom')").formatted(noteId, vaultId));
                statement.execute(("INSERT INTO platform.note_snapshots (note_id, server_sequence, state) "
                    + "VALUES ('%s', 1, decode('00', 'hex'))").formatted(noteId));
                statement.execute(("INSERT INTO platform.note_tombstones "
                    + "(vault_id, note_id, operation_id, deleted_by) VALUES ('%s', '%s', gen_random_uuid(), 'tom')")
                    .formatted(vaultId, UUID.randomUUID()));
                statement.execute(("INSERT INTO platform.audit_events (vault_id, note_id, actor, action) "
                    + "VALUES ('%s', '%s', 'tom', 'note.created')").formatted(vaultId, noteId));

                statement.execute("DELETE FROM platform.vaults WHERE id = '%s'".formatted(vaultId));

                assertThat(countWhere(statement, "platform.notes", vaultId)).isZero();
                assertThat(countWhere(statement, "platform.note_tombstones", vaultId)).isZero();
                assertThat(countWhere(statement, "platform.audit_events", vaultId))
                    .as("Audit-Events müssen das Löschen des Vaults überleben (Fehlerklasse 4)")
                    .isEqualTo(1);
            }
        }

        private int countWhere(Statement statement, String table, UUID vaultId) throws Exception {
            try (ResultSet rs = statement.executeQuery(
                "SELECT count(*) FROM %s WHERE vault_id = '%s'".formatted(table, vaultId))) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
