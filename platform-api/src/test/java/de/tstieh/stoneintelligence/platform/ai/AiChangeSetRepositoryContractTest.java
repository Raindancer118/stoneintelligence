package de.tstieh.stoneintelligence.platform.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemeinsamer Vertrag fuer Fake- und Postgres-Implementierung. */
public abstract class AiChangeSetRepositoryContractTest {

    protected abstract AiChangeSetRepository repository();

    /** Liefert einen existierenden Vault (Postgres braucht die Fremdschluessel-Zeile). */
    protected abstract VaultId existingVault();

    private AiChangeSetRepository changeSets;
    private VaultId vaultId;
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        changeSets = repository();
        vaultId = existingVault();
    }

    private AiChange change(AiChangeSet set, AiChange.Kind kind, String before, String after, Instant at) {
        return new AiChange(UUID.randomUUID(), set.id(), NoteId.newId(), "Wissen/A.md", kind, before, after, at);
    }

    @Test
    void should_keepAllFields_andScopeByVault() {
        var created = changeSets.create(vaultId, "gemini", "ki:Gemini", "tom", "Vorlesung.pdf", now);

        var found = changeSets.find(vaultId, created.id()).orElseThrow();

        assertThat(found).isEqualTo(new AiChangeSet(created.id(), vaultId, "gemini", "ki:Gemini", "tom", "Vorlesung.pdf", now, null));
        assertThat(changeSets.find(existingVault(), created.id())).isEmpty();
    }

    @Test
    void should_returnChangesInTheOrderTheyHappened_withExactTexts() {
        var set = changeSets.create(vaultId, "gemini", "ki:Gemini", "tom", "x", now);
        var first = change(set, AiChange.Kind.CREATED, "", "a\n", now);
        var second = change(set, AiChange.Kind.UPDATED, "a\n", "a\nb – ü\n", now.plusSeconds(1));
        changeSets.addChange(first);
        changeSets.addChange(second);

        assertThat(changeSets.changes(set.id())).containsExactly(first, second);
    }

    @Test
    void should_markRevertedOnlyOnce() {
        var set = changeSets.create(vaultId, "gemini", "ki:Gemini", "tom", "x", now);

        assertThat(changeSets.markReverted(set.id(), now)).isTrue();
        assertThat(changeSets.markReverted(set.id(), now)).isFalse();
        assertThat(changeSets.find(vaultId, set.id()).orElseThrow().revertedAt()).isEqualTo(now);
    }

    @Test
    void should_listNewestFirst() {
        var older = changeSets.create(vaultId, "gemini", "ki:Gemini", "tom", "alt", now);
        var newer = changeSets.create(vaultId, "gemini", "ki:Gemini", "tom", "neu", now.plusSeconds(60));

        assertThat(changeSets.list(vaultId, 10)).extracting(AiChangeSet::id).containsExactly(newer.id(), older.id());
        assertThat(changeSets.list(vaultId, 1)).extracting(AiChangeSet::id).containsExactly(newer.id());
    }

    // Datensparsamkeit (DSGVO Art. 5 Abs. 1 lit. e): Textkopien fuer das Rueckgaengigmachen werden
    // nur so lange aufbewahrt, wie Rueckgaengig angeboten wird.
    @Test
    void should_purgeChangeSetsOlderThanTheCutoff_withTheirTexts() {
        var old = changeSets.create(vaultId, "gemini", "ki:Gemini", "tom", "alt", now.minus(Duration.ofDays(91)));
        changeSets.addChange(change(old, AiChange.Kind.CREATED, "", "alt\n", now.minus(Duration.ofDays(91))));
        var recent = changeSets.create(vaultId, "gemini", "ki:Gemini", "tom", "neu", now);

        assertThat(changeSets.purgeCreatedBefore(now.minus(Duration.ofDays(90)))).isEqualTo(1);

        assertThat(changeSets.find(vaultId, old.id())).isEmpty();
        assertThat(changeSets.changes(old.id())).isEmpty();
        assertThat(changeSets.find(vaultId, recent.id())).isPresent();
    }
}
