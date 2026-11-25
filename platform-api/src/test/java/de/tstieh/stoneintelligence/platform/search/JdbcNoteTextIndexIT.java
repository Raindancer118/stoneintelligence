package de.tstieh.stoneintelligence.platform.search;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.sync.relay.JdbcSnapshotStore;
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

@Testcontainers
class JdbcNoteTextIndexIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("platform").locations("classpath:db/migration/platform").load().migrate();
        jdbc = JdbcClient.create(new SimpleDriverDataSource(
            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private final JdbcNoteTextIndex index = new JdbcNoteTextIndex(jdbc);

    private VaultId vault() {
        return new JdbcVaultRepository(jdbc).create("Suche " + NoteId.newId().value()).id();
    }

    private NoteId note(VaultId vault) {
        return new JdbcNoteRepository(jdbc).create(vault, "n-" + NoteId.newId().value() + ".md", NoteLevel.of(1), "tom", NoteKind.NOTE).id();
    }

    @Test
    void should_queueANote_whenAnUpdateArrives_andHandItOutOnlyOnce() {
        var vault = vault();
        var note = note(vault);
        var snapshots = new JdbcSnapshotStore(jdbc);
        snapshots.append(note, new byte[] {1}, false, "tom");
        snapshots.append(note, new byte[] {2}, false, "tom");

        var first = index.claim(1000).stream().filter(q -> q.noteId().equals(note)).toList();
        var second = index.claim(1000).stream().filter(q -> q.noteId().equals(note)).toList();

        assertThat(first).containsExactly(new NoteTextIndex.Queued(note, vault));
        assertThat(second).isEmpty();
        index.requeue(note);
        assertThat(index.claim(1000)).extracting(NoteTextIndex.Queued::noteId).contains(note);
    }

    // ADR 0018: ein Canvas hat keinen Text - sein Yjs-Update stellt ihn nicht in die Warteschlange.
    @Test
    void should_notQueueACanvas_whenAnUpdateArrives() {
        var vault = vault();
        var canvas = new JdbcNoteRepository(jdbc).create(vault, "b-" + NoteId.newId().value() + ".canvas", NoteLevel.of(1), "tom", NoteKind.CANVAS).id();
        new JdbcSnapshotStore(jdbc).append(canvas, new byte[] {1}, false, "tom");

        assertThat(index.claim(1000)).extracting(NoteTextIndex.Queued::noteId).doesNotContain(canvas);
    }

    @Test
    void should_findByWordPrefix_withinTheVaultOnly_andMarkThePassage() {
        var vault = vault();
        var other = vault();
        var plan = note(vault);
        var foreign = note(other);
        index.upsert(plan, vault, 3, "Die Bestellmengen werden nach Andler berechnet. Größe und Kosten.");
        index.upsert(foreign, other, 1, "Bestellmengen im anderen Vault");

        assertThat(index.matching(vault, SearchQuery.tsquery("bestell andler").orElseThrow(), 10)).containsExactly(plan);
        assertThat(index.matching(vault, SearchQuery.tsquery("größe").orElseThrow(), 10)).containsExactly(plan);
        assertThat(index.excerpts(vault, List.of(plan), SearchQuery.tsquery("bestell").orElseThrow()).get(plan))
            .contains(NoteTextIndex.MARK_START + "Bestellmengen" + NoteTextIndex.MARK_END);
    }

    @Test
    void should_matchOneWordOfAQuestion_andHandOutTheBodies_ofThisVaultOnly() {
        var vault = vault();
        var other = vault();
        var plan = note(vault);
        var unrelated = note(vault);
        var foreign = note(other);
        index.upsert(plan, vault, 1, "Die Lichtreaktion läuft im Thylakoid ab.");
        index.upsert(unrelated, vault, 1, "Kochen mit Gemüse");
        index.upsert(foreign, other, 1, "Lichtreaktion im anderen Vault");

        var question = SearchQuery.anyOf("Wo läuft die Lichtreaktion ab?").orElseThrow();

        assertThat(index.matching(vault, question, 10)).containsExactly(plan);
        assertThat(index.bodies(vault, List.of(plan, foreign))).containsOnlyKeys(plan);
        assertThat(index.bodies(vault, List.of(plan)).get(plan)).startsWith("Die Lichtreaktion");
    }

    @Test
    void should_keepTheNewerText_whenAnOlderIndexRunFinishesLate() {
        var vault = vault();
        var note = note(vault);
        index.upsert(note, vault, 5, "neuer Stand");
        index.upsert(note, vault, 4, "alter Stand");

        assertThat(index.matching(vault, "neuer:*", 10)).containsExactly(note);
        assertThat(index.matching(vault, "alter:*", 10)).isEmpty();
    }
}
