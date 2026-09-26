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

    /** Eine Notiz, die es im Vault wirklich gibt (die Jdbc-Variante hat Fremdschluessel darauf). */
    protected abstract de.tstieh.stoneintelligence.domain.id.NoteId existingNote(VaultId vaultId, String path);

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

    // ADR 0012: gespeichert wird nur, was Rueckgaengig braucht - nicht der Notiztext.
    @Test
    void should_keepTheInsertedLinks_ofALinkingChange() {
        var set = changeSets.create(vaultId, "gemini", "ki:Gemini", "tom", "Verlinkung", now);
        var inline = new de.tstieh.stoneintelligence.domain.link.LinkText.Insertion(null,
            de.tstieh.stoneintelligence.domain.link.LinkText.Placement.INLINE, "[[Licht|licht]]", "licht", false, "");
        var related = new de.tstieh.stoneintelligence.domain.link.LinkText.Insertion(null,
            de.tstieh.stoneintelligence.domain.link.LinkText.Placement.RELATED, "- [[Photon]]", null, true, "\n## Verwandt\n\n");
        var linked = new AiChange(java.util.UUID.randomUUID(), set.id(), de.tstieh.stoneintelligence.domain.id.NoteId.newId(),
            "a.md", AiChange.Kind.LINKED, "", "", now, java.util.List.of(inline, related));

        changeSets.addChange(linked);

        assertThat(changeSets.changes(set.id())).singleElement().satisfies(stored -> {
            assertThat(stored.kind()).isEqualTo(AiChange.Kind.LINKED);
            assertThat(stored.links()).containsExactly(inline, related);
        });
    }

    @Test
    void should_rememberWhichPairsWereLinkedOnce_perSourceAndVault() {
        var source = existingNote(vaultId, "Quelle.md");
        var target = existingNote(vaultId, "Ziel.md");
        var other = existingNote(vaultId, "Anders.md");

        changeSets.rememberLink(vaultId, source, target, now);
        changeSets.rememberLink(vaultId, source, target, now.plusSeconds(1));

        assertThat(changeSets.linkedTargets(vaultId, source)).containsExactly(target);
        assertThat(changeSets.linkedTargets(vaultId, other)).isEmpty();
    }

    @Test
    void should_rememberRejections_withTheTextVersions_andReplaceThem() {
        var source = existingNote(vaultId, "Quelle.md");
        var target = existingNote(vaultId, "Ziel.md");

        changeSets.rememberRejection(vaultId, source, target, "s1", "t1", now);
        changeSets.rememberRejection(vaultId, source, target, "s2", "t2", now.plusSeconds(1));

        assertThat(changeSets.rejectedTargets(vaultId, source)).containsExactly(java.util.Map.entry(target, java.util.List.of("s2", "t2")));
        assertThat(changeSets.rejectedTargets(vaultId, target)).isEmpty();
    }

    @Test
    void should_rememberTheKindOfRelation_perPair() {
        var source = existingNote(vaultId, "Quelle.md");
        var target = existingNote(vaultId, "Ziel.md");

        changeSets.rememberRelation(vaultId, source, target, LinkRelation.PART_OF, null, now);

        assertThat(changeSets.relationsFrom(vaultId, source)).containsExactly(java.util.Map.entry(target, LinkRelation.PART_OF));
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
