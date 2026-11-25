package de.tstieh.stoneintelligence.platform.ask;

import java.util.List;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.ai.AiService;
import de.tstieh.stoneintelligence.platform.ai.FakeNoteEmbeddingRepository;
import de.tstieh.stoneintelligence.platform.ai.NoteEmbeddingRepository;
import de.tstieh.stoneintelligence.platform.ai.NoteEmbeddingRepositoryContractTest;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.search.FakeNoteTextIndex;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AskRetrievalTest {

    private static final AiService GEMINI = new AiService("gemini", "Gemini", Set.of(1));
    private static final AiService LOKAL = new AiService("lokal", "Ollama lokal", Set.of(1, 2), true);

    private final VaultId vault = VaultId.newId();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeNoteTextIndex texts = new FakeNoteTextIndex();
    private final FakeNoteEmbeddingRepository embeddings = new FakeNoteEmbeddingRepository(notes);
    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final VaultAccessGuard access = new VaultAccessGuard(authorization, grants);
    private final AskRetrieval retrieval = new AskRetrieval(texts, embeddings, notes, access, AiService::mayProcess, 0.8);

    @BeforeEach
    void members() {
        var role = authorization.createRole(vault, "leser", Set.of(Permission.READ));
        var group = authorization.createGroup(vault, "leser");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "ben");
    }

    private NoteId note(String path, String author, int level, String text) {
        var note = notes.create(vault, path, NoteLevel.of(level), author);
        texts.upsert(note.id(), vault, 1, text);
        return note.id();
    }

    private static float[] vector(int axis, double tilt) {
        return NoteEmbeddingRepositoryContractTest.vector(axis, tilt);
    }

    private AskRetrieval.Result ask(AiService service, Set<String> consents, String question, float[] vector) {
        return retrieval.retrieve(vault, "ben", service, consents, question, vector);
    }

    @Test
    void should_findPassagesByWords_asTheMatchingSectionOfEachNote_numberedFromOne() {
        note("Biologie/Photosynthese.md", "tom", 1,
            "# Photosynthese\n\nPflanzen wandeln Licht um.\n\n## Lichtreaktion\n\nDie Lichtreaktion läuft im Thylakoid ab.\n\n## Calvin-Zyklus\n\nIm Stroma.");
        note("Kochen.md", "tom", 1, "Gemüse schneiden und braten.");

        var result = ask(LOKAL, Set.of(), "Wo läuft die Lichtreaktion ab?", null);

        assertThat(result.passages()).singleElement().satisfies(passage -> {
            assertThat(passage.n()).isEqualTo(1);
            assertThat(passage.path()).isEqualTo("Biologie/Photosynthese.md");
            assertThat(passage.title()).isEqualTo("Photosynthese");
            assertThat(passage.heading()).isEqualTo("Lichtreaktion");
            assertThat(passage.text()).isEqualTo("Die Lichtreaktion läuft im Thylakoid ab.");
        });
        assertThat(result.omittedForConsent()).isZero();
    }

    @Test
    void should_useTheSemanticChunk_evenWithoutSharedWords_andRankNotesFoundByBoth_first() {
        var both = note("Beides.md", "tom", 1, "# Beides\n\n## Anderes\n\nNichts davon.\n\n## Chlorophyll\n\nBlattgrün fängt Sonnenlicht ein.");
        var semanticOnly = note("Semantik.md", "tom", 1, "# Semantik\n\nPflanzen fangen Energie der Sonne.");
        note("Wortsuche.md", "tom", 1, "Blattgrün ist grün.");
        embeddings.replace(vault, both, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, "Anderes", vector(9, 0)),
            new NoteEmbeddingRepository.Chunk(1, "Chlorophyll", vector(5, 0.05))));
        embeddings.replace(vault, semanticOnly, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, "Semantik", vector(5, 0.2))));

        var result = ask(LOKAL, Set.of(), "Was macht Blattgrün?", vector(5, 0));

        assertThat(result.passages()).extracting(AskRetrieval.Passage::path)
            .startsWith("Beides.md").containsExactlyInAnyOrder("Beides.md", "Semantik.md", "Wortsuche.md");
        assertThat(result.passages().getFirst().text()).isEqualTo("Blattgrün fängt Sonnenlicht ein.");
        assertThat(result.passages()).extracting(AskRetrieval.Passage::n).containsExactly(1, 2, 3);
    }

    @Test
    void should_ignoreSemanticHitsBelowTheThreshold() {
        var far = note("Fern.md", "tom", 1, "Irgendwas anderes.");
        embeddings.replace(vault, far, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, "Fern", vector(7, 1.2))));

        assertThat(ask(LOKAL, Set.of(), "Frage ohne Treffer?", vector(5, 0)).passages()).isEmpty();
    }

    @Test
    void should_neverReturnNotesThePersonMayNotRead_beforeAnythingLeavesTheServer() {
        note("Privat/Gehalt.md", "tom", 1, "Das Gehalt beträgt viel.");
        note("Oeffentlich/Gehalt.md", "tom", 1, "Das Gehalt steht im Vertrag.");
        grants.put(vault, GrantTarget.folder("Privat"), GrantScope.user("ben"), Set.of(), "tom");

        var result = ask(LOKAL, Set.of(), "Wie hoch ist das Gehalt?", null);

        assertThat(result.passages()).extracting(AskRetrieval.Passage::path).containsExactly("Oeffentlich/Gehalt.md");
        assertThat(result.omittedForConsent()).as("unreadable notes are not even counted").isZero();
    }

    @Test
    void should_respectTheLevelsOfTheService() {
        note("Intern.md", "tom", 2, "Interne Zahlen zur Planung.");
        note("Offen.md", "tom", 1, "Offene Zahlen zur Planung.");

        assertThat(ask(GEMINI, Set.of("tom"), "Zahlen zur Planung?", null).passages()).extracting(AskRetrieval.Passage::path)
            .containsExactly("Offen.md");
        assertThat(ask(LOKAL, Set.of(), "Zahlen zur Planung?", null).passages()).extracting(AskRetrieval.Passage::path)
            .containsExactlyInAnyOrder("Intern.md", "Offen.md");
    }

    @Test
    void should_sendToAnExternalService_onlyNotesWhoseAuthorConsented_orWhichAnAiWrote_andCountTheRest() {
        note("Von-Tom.md", "tom", 1, "Der Plan nennt drei Meilensteine.");
        note("Von-Anna.md", "anna", 1, "Der Plan nennt vier Meilensteine.");
        note("Von-Ki.md", "ki:Gemini", 1, "Der Plan nennt fünf Meilensteine.");

        var external = ask(GEMINI, Set.of("tom"), "Welche Meilensteine nennt der Plan?", null);

        assertThat(external.passages()).extracting(AskRetrieval.Passage::path).containsExactlyInAnyOrder("Von-Tom.md", "Von-Ki.md");
        assertThat(external.omittedForConsent()).isEqualTo(1);
        var local = ask(LOKAL, Set.of(), "Welche Meilensteine nennt der Plan?", null);
        assertThat(local.passages()).hasSize(3);
        assertThat(local.omittedForConsent()).isZero();
    }

    @Test
    void should_keepToTheBudget_atMostTwoPassagesPerNote_andAtMostEightInTotal() {
        var section = "Der Wert wird berechnet. ".repeat(60);
        for (var i = 0; i < 12; i++) {
            note("Berechnung " + i + ".md", "tom", 1, "# Eins\n\n" + section + "\n\n# Zwei\n\n" + section + "\n\n# Drei\n\n" + section);
        }

        var result = ask(LOKAL, Set.of(), "Wie wird der Wert berechnet?", null);

        assertThat(result.passages()).hasSizeLessThanOrEqualTo(AskRetrieval.MAX_PASSAGES);
        assertThat(result.passages().stream().mapToInt(passage -> AskRetrieval.tokens(passage)).sum())
            .isLessThanOrEqualTo(AskRetrieval.BUDGET_TOKENS);
        assertThat(result.passages().stream().collect(java.util.stream.Collectors.groupingBy(AskRetrieval.Passage::noteId,
            java.util.stream.Collectors.counting())).values()).allMatch(count -> count <= AskRetrieval.MAX_PER_NOTE);
    }

    @Test
    void should_giveNothing_whenNothingMatches_orTheQuestionHasNoContentWords() {
        note("Kochen.md", "tom", 1, "Gemüse schneiden.");

        assertThat(ask(LOKAL, Set.of(), "Wie funktioniert Photosynthese?", null).passages()).isEmpty();
        assertThat(ask(LOKAL, Set.of(), "Was ist das?", null).passages()).isEmpty();
    }
}
