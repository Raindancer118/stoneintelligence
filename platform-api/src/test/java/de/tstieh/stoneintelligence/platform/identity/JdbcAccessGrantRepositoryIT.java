package de.tstieh.stoneintelligence.platform.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.vault.JdbcNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Derselbe Vertrag wie {@link FakeAccessGrantRepositoryTest}, gegen echtes Postgres. */
@Testcontainers
class JdbcAccessGrantRepositoryIT extends AccessGrantRepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static JdbcClient jdbcClient;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform").locations("classpath:db/migration/platform").load().migrate();
        jdbcClient = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Override
    protected AccessGrantRepository repository() {
        return new JdbcAccessGrantRepository(jdbcClient);
    }

    @Override
    protected VaultId newVault() {
        return new JdbcVaultRepository(jdbcClient).create("Freigaben-Test").id();
    }

    @Override
    protected NoteId newNote(VaultId vaultId, String path) {
        return new JdbcNoteRepository(jdbcClient).create(vaultId, path, NoteLevel.of(1), "tom").id();
    }

    @Override
    protected void renameNote(VaultId vaultId, NoteId noteId, String path) {
        new JdbcNoteRepository(jdbcClient).rename(vaultId, noteId, path);
    }

    @Override
    protected void deleteNote(VaultId vaultId, NoteId noteId) {
        new JdbcNoteRepository(jdbcClient).delete(vaultId, noteId, UUID.randomUUID().toString(), "tom");
    }

    // Bestehende Pfadregeln duerfen beim Update weder verloren gehen noch ihre Bedeutung aendern.
    @Test
    void should_turnExistingPathRulesIntoGrants_whenMigrating() {
        var fresh = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES);
        fresh.start();
        try {
            var client = JdbcClient.create(new SimpleDriverDataSource(
                new org.postgresql.Driver(), fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword()));
            var flyway = Flyway.configure().dataSource(fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword())
                .schemas("platform").locations("classpath:db/migration/platform");
            flyway.target("13").load().migrate();
            var vault = new JdbcVaultRepository(client).create("Bestand").id();
            var note = client.sql("""
                    INSERT INTO platform.notes (vault_id, path, note_level, created_by) VALUES (:vault, 'Team/plan.md', 1, 'tom')
                    RETURNING id""").param("vault", vault.value()).query(UUID.class).single();
            // Mit dem Schema von damals (V13), nicht mit dem heutigen Repository.
            for (var rule : List.of(
                    List.of("Privat/", "USER", "ben", "DENY"),
                    List.of("Privat/Geteilt", "USER", "ben", "ALLOW"),
                    List.of("Team/plan.md", "EVERYONE", "", "DENY"),
                    List.of("", "USER", "eve", "DENY"),
                    List.of("Doppelt", "EVERYONE", "", "ALLOW"),
                    List.of("Doppelt", "EVERYONE", "", "DENY"))) {
                client.sql("""
                        INSERT INTO platform.path_rules (vault_id, path_prefix, scope_type, scope_subject, effect)
                        VALUES (:vault, :prefix, :type, NULLIF(:subject, ''), :effect)""")
                    .param("vault", vault.value()).param("prefix", rule.get(0)).param("type", rule.get(1))
                    .param("subject", rule.get(2)).param("effect", rule.get(3)).update();
            }

            flyway.target("latest").load().migrate();

            var grants = new JdbcAccessGrantRepository(client).list(vault);
            assertThat(grants)
                .extracting(AccessGrant::target, AccessGrant::scope, AccessGrant::permissions)
                .containsExactlyInAnyOrder(
                    org.assertj.core.groups.Tuple.tuple(GrantTarget.folder("Privat"), GrantScope.user("ben"), Set.of()),
                    org.assertj.core.groups.Tuple.tuple(GrantTarget.folder("Privat/Geteilt"), GrantScope.user("ben"), null),
                    org.assertj.core.groups.Tuple.tuple(GrantTarget.entry(NoteId.of(note), "Team/plan.md"), GrantScope.everyone(), Set.of()),
                    org.assertj.core.groups.Tuple.tuple(GrantTarget.folder(""), GrantScope.user("eve"), Set.of()),
                    org.assertj.core.groups.Tuple.tuple(GrantTarget.folder("Doppelt"), GrantScope.everyone(), Set.of()));
        } finally {
            fresh.stop();
        }
    }
}
