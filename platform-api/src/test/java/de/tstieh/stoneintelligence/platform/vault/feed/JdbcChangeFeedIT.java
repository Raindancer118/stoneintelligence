package de.tstieh.stoneintelligence.platform.vault.feed;

import java.sql.DriverManager;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.JdbcAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.sync.relay.JdbcSnapshotStore;
import de.tstieh.stoneintelligence.platform.vault.JdbcFolderRepository;
import de.tstieh.stoneintelligence.platform.vault.JdbcNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.JdbcVaultRepository;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
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

@Testcontainers
class JdbcChangeFeedIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static JdbcClient jdbc;
    private JdbcChangeFeed feed;
    private JdbcNoteRepository notes;
    private VaultId vault;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform").locations("classpath:db/migration/platform").load().migrate();
        jdbc = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void setUp() {
        feed = new JdbcChangeFeed(jdbc);
        notes = new JdbcNoteRepository(jdbc);
        vault = new JdbcVaultRepository(jdbc).create("Konzern").id();
    }

    private ChangeFeed.Batch readAll(FeedCursor from) {
        return feed.read(vault, from, 1000);
    }

    @Test
    void should_reportCreatedTypedRenamedAndDeletedEntries_butNotMereOpening() {
        var start = FeedCursor.at(feed.now());
        var plan = notes.create(vault, "Team/plan.md", NoteLevel.of(1), "tom", NoteKind.NOTE);
        var typed = notes.create(vault, "Team/typed.md", NoteLevel.of(1), "tom", NoteKind.NOTE);
        var gone = notes.create(vault, "Team/gone.md", NoteLevel.of(1), "tom", NoteKind.NOTE);
        var first = readAll(start);
        assertThat(first.changed()).containsExactlyInAnyOrder(plan.id(), typed.id(), gone.id());

        notes.markOpened(vault, plan.id(), "ben", java.time.Instant.now());
        new JdbcSnapshotStore(jdbc).append(typed.id(), new byte[] {1}, false, "ben");
        notes.delete(vault, gone.id(), "op-1", "tom");
        var second = readAll(first.next());

        assertThat(second.changed()).containsExactlyInAnyOrder(typed.id(), gone.id());
        notes.rename(vault, plan.id(), "Team/plan-2026.md");
        assertThat(readAll(second.next()).changed()).containsExactly(plan.id());
    }

    @Test
    void should_pageThroughABurst_withoutLosingOrRepeating() {
        var start = FeedCursor.at(feed.now());
        var created = new java.util.HashSet<NoteId>();
        for (var i = 0; i < 25; i++) {
            created.add(notes.create(vault, "Import/n" + i + ".md", NoteLevel.of(1), "ki:Gemini", NoteKind.NOTE).id());
        }
        var seen = new java.util.ArrayList<NoteId>();
        var batch = feed.read(vault, start, 7);
        seen.addAll(batch.changed());
        while (batch.more()) {
            batch = feed.read(vault, batch.next(), 7);
            seen.addAll(batch.changed());
        }

        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    void should_reportFoldersAndWhoseAccessChanged() {
        var start = FeedCursor.at(feed.now());
        var folders = new JdbcFolderRepository(jdbc);
        folders.ensure(vault, "Kunden/Archiv", "tom");
        folders.ensure(vault, "Alt", "tom");
        folders.deleteTree(vault, "Alt");
        new JdbcAccessGrantRepository(jdbc).put(vault, GrantTarget.folder("Kunden"), GrantScope.user("ben"), Set.of(), "tom");

        var batch = readAll(start);

        assertThat(batch.foldersChanged()).contains("Kunden", "Kunden/Archiv").doesNotContain("Alt");
        assertThat(batch.foldersRemoved()).containsExactly("Alt");
        assertThat(batch.accessEvents()).containsExactly(new ChangeFeed.AccessEvent("USER", "ben", "Kunden", null));
    }

    // Kern des ADR: eine frueher begonnene, spaeter committende Transaktion darf nie uebersprungen werden.
    @Test
    void should_neverSkipATransactionThatCommitsLate() throws Exception {
        var start = FeedCursor.at(feed.now());
        try (var slow = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            slow.setAutoCommit(false);
            var lateId = java.util.UUID.randomUUID();
            try (var insert = slow.prepareStatement(
                    "INSERT INTO platform.notes (id, vault_id, path, note_level, created_by) VALUES (?, ?, 'spaet.md', 1, 'tom')")) {
                insert.setObject(1, lateId);
                insert.setObject(2, vault.value());
                insert.executeUpdate();
            }
            var quick = notes.create(vault, "schnell.md", NoteLevel.of(1), "tom", NoteKind.NOTE);

            var whileSlowRuns = readAll(start);
            assertThat(whileSlowRuns.changed()).as("die schnelle kommt erst, wenn die langsame durch ist").isEmpty();

            slow.commit();
            var after = readAll(whileSlowRuns.next());
            assertThat(after.changed()).containsExactlyInAnyOrder(NoteId.of(lateId), quick.id());
        }
    }
}
