package de.tstieh.stoneintelligence.platform.identity;

import java.util.Set;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.vault.JdbcNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** V19: jede Aenderung, die Rechte verschiebt, zaehlt die Zugriffs-Version ihres Vaults hoch - und nur ihres. */
@Testcontainers
class JdbcAccessVersionsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform").locations("classpath:db/migration/platform").load().migrate();
        jdbc = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void should_countEveryChangeThatMovesAccess_perVault() {
        var vaults = new JdbcVaultRepository(jdbc);
        var versions = new JdbcAccessVersions(jdbc);
        var authorization = new JdbcAuthorizationRepository(jdbc);
        var grants = new JdbcAccessGrantRepository(jdbc);
        var notes = new JdbcNoteRepository(jdbc);
        var vault = vaults.create("Konzern").id();
        var other = vaults.create("Anderer").id();
        var otherBefore = versions.current(other);

        var steps = new java.util.ArrayList<Long>();
        steps.add(versions.current(vault));
        var role = authorization.createRole(vault, "lesen", Set.of(Permission.READ));
        steps.add(versions.current(vault));
        var group = authorization.createGroup(vault, "alle");
        steps.add(versions.current(vault));
        authorization.assignRole(group.id(), role.id());
        steps.add(versions.current(vault));
        authorization.addMember(group.id(), "ben");
        steps.add(versions.current(vault));
        grants.put(vault, GrantTarget.folder("Team"), GrantScope.user("ben"), Set.of(), "tom");
        steps.add(versions.current(vault));
        var note = notes.create(vault, "Team/plan.md", NoteLevel.of(1), "tom", NoteKind.NOTE);
        var afterCreate = versions.current(vault);
        grants.put(vault, GrantTarget.entry(note.id(), note.path()), GrantScope.user("ben"), Set.of(Permission.READ), "tom");
        steps.add(versions.current(vault));
        notes.rename(vault, note.id(), "Team/plan-2026.md");
        steps.add(versions.current(vault));

        assertThat(steps).isSorted().doesNotHaveDuplicates();
        assertThat(afterCreate).as("anlegen verschiebt keine Rechte").isEqualTo(steps.get(5));
        assertThat(versions.current(other)).isEqualTo(otherBefore);
    }
}
