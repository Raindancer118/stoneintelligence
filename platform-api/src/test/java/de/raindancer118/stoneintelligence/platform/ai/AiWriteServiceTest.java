package de.raindancer118.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.sync.relay.FakeSnapshotStore;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRoomRegistry;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.raindancer118.stoneintelligence.platform.sync.yjs.YjsBridge;
import de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiWriteServiceTest {

    private static YjsBridge yjs;

    @BeforeAll
    static void loadYjs() {
        yjs = YjsBridge.load();
    }

    @AfterAll
    static void closeYjs() {
        yjs.close();
    }

    /** Externer Anbieter: nur oeffentliche Notizen. */
    private static final AiService EXTERN = new AiService("gemini", "Gemini", java.util.Set.of(1));
    /** Selbst gehostet: darf auch interne Levels. */
    private static final AiService LOKAL = new AiService("lokal", "Ollama lokal", java.util.Set.of(1, 2, 3));
    private static final String AGENT = "ki:Gemini";
    private final VaultId vaultId = VaultId.newId();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeSnapshotStore snapshots = new FakeSnapshotStore();
    private final SyncRelayService relay = new SyncRelayService(snapshots, new SyncRoomRegistry());
    private final List<String> audit = new ArrayList<>();
    private final FakeAiChangeSetRepository changeSets = new FakeAiChangeSetRepository();
    private final AiWriteService service = new AiWriteService(notes, snapshots, relay, yjs, new VaultAnnouncementService(),
        (vault, note, actor, action, payload) -> audit.add(actor + " " + action), new AiServiceDirectory(List.of(EXTERN, LOKAL)), changeSets, () -> Instant.parse("2026-09-23T12:00:00Z"));

    private AiChangeSet newChangeSet() {
        return service.startChangeSet(vaultId, EXTERN, "tom", "Vorlesung.pdf");
    }

    /** Wie ein Mensch schreibt: ueber den normalen Yjs-Weg, nicht ueber den KI-Dienst. */
    private NoteId humanNote(String path, String text) {
        var note = notes.create(vaultId, path, NoteLevel.of(1), "tom");
        relay.saveIfCurrent(note.id(), 0, yjs.change(List.of(), text).orElseThrow());
        return note.id();
    }

    private void humanEdit(NoteId noteId, String text) {
        var history = snapshots.listSince(noteId, 0);
        relay.saveIfCurrent(noteId, history.size(), yjs.change(history.stream().map(r -> r.payload()).toList(), text).orElseThrow());
    }

    @Nested
    class Schreiben {

        @Test
        void should_createANoteAsTheAgent_readableAsYjsText() {
            var changeSet = newChangeSet();

            var written = service.createNote(vaultId, changeSet.id(), "Wissen/Photosynthese.md", "# Photosynthese\n\nText.\n", NoteLevel.of(1));

            assertThat(service.readText(vaultId, written.noteId(), EXTERN)).isEqualTo("# Photosynthese\n\nText.\n");
            assertThat(notes.findById(vaultId, written.noteId()).orElseThrow().createdBy()).isEqualTo(AGENT);
            assertThat(audit).contains(AGENT + " note.created", AGENT + " note.content-updated");
        }

        @Test
        void should_updateItsOwnNote_andRecordBeforeAndAfter() {
            var changeSet = newChangeSet();
            var created = service.createNote(vaultId, changeSet.id(), "A.md", "alt\n", NoteLevel.of(1));

            service.updateNote(vaultId, changeSet.id(), created.noteId(), "alt\nneu\n");

            assertThat(service.readText(vaultId, created.noteId(), EXTERN)).isEqualTo("alt\nneu\n");
            assertThat(changeSets.changes(changeSet.id())).extracting(AiChange::kind, AiChange::textBefore, AiChange::textAfter)
                .containsExactly(
                    org.assertj.core.groups.Tuple.tuple(AiChange.Kind.CREATED, "", "alt\n"),
                    org.assertj.core.groups.Tuple.tuple(AiChange.Kind.UPDATED, "alt\n", "alt\nneu\n"));
        }

        // Toms Vorgabe: KI veraendert standardmaessig keine von Menschen angelegten Notizen.
        @Test
        void should_refuseToEditHumanNotes() {
            var human = humanNote("Mensch.md", "von Tom\n");

            assertThatThrownBy(() -> service.updateNote(vaultId, newChangeSet().id(), human, "von der KI\n"))
                .isInstanceOf(AiWriteRefusedException.class);
            assertThat(service.readText(vaultId, human, EXTERN)).isEqualTo("von Tom\n");
        }

        @Test
        void should_refuseToCreateOverAHumanNotesPath() {
            humanNote("Belegt.md", "Tom\n");

            assertThatThrownBy(() -> service.createNote(vaultId, newChangeSet().id(), "Belegt.md", "KI\n", NoteLevel.of(1)))
                .isInstanceOf(AiWriteRefusedException.class);
        }

        // Toms Vorgabe: je KI-Dienst einstellbar, welche Levels er verarbeiten darf.
        @Test
        void should_letEachServiceReadOnlyItsLevels() {
            var intern = notes.create(vaultId, "Intern.md", NoteLevel.of(2), "tom");
            relay.saveIfCurrent(intern.id(), 0, yjs.change(List.of(), "intern\n").orElseThrow());

            assertThat(service.readText(vaultId, intern.id(), LOKAL)).isEqualTo("intern\n");
            assertThatThrownBy(() -> service.readText(vaultId, intern.id(), EXTERN)).isInstanceOf(AiWriteRefusedException.class);
        }

        @Test
        void should_neverHandOutUnsyncedOrEncryptedNotes_whateverTheConfiguration() {
            var secret = notes.create(vaultId, "Geheim.md", NoteLevel.of(101), "tom");
            var allLevels = new AiService("alles", "Alles", java.util.Set.of(1, 2, 3));

            assertThatThrownBy(() -> service.readText(vaultId, secret.id(), allLevels)).isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> new AiService("x", "X", java.util.Set.of(1, 101))).isInstanceOf(IllegalArgumentException.class);
        }

        // Abgeleitetes Wissen erbt das Level seiner Quelle - ein Dienst darf nur auf Levels schreiben,
        // die er auch verarbeiten darf, sonst koennte er Vertrauliches "herabstufen" oder erzeugen.
        @Test
        void should_writeOnlyOnLevelsTheServiceIsAllowedFor() {
            var lokalSet = service.startChangeSet(vaultId, LOKAL, "tom", "Intern.pdf");

            var written = service.createNote(vaultId, lokalSet.id(), "Intern/Wissen.md", "x\n", NoteLevel.of(2));

            assertThat(notes.findById(vaultId, written.noteId()).orElseThrow().level()).isEqualTo(NoteLevel.of(2));
            assertThat(notes.findById(vaultId, written.noteId()).orElseThrow().createdBy()).isEqualTo("ki:Ollama lokal");
            assertThatThrownBy(() -> service.createNote(vaultId, newChangeSet().id(), "Extern.md", "x\n", NoteLevel.of(2)))
                .isInstanceOf(AiWriteRefusedException.class);
        }

        // Wird ein Dienst aus der Konfiguration genommen, schreibt ein noch laufender Job nicht weiter.
        @Test
        void should_stopWriting_whenTheServiceIsNoLongerConfigured() {
            var changeSet = newChangeSet();
            var afterRestart = new AiWriteService(notes, snapshots, relay, yjs, new VaultAnnouncementService(),
                (vault, note, actor, action, payload) -> { }, new AiServiceDirectory(List.of(LOKAL)), changeSets, Instant::now);

            assertThatThrownBy(() -> afterRestart.createNote(vaultId, changeSet.id(), "X.md", "x\n", NoteLevel.of(1)))
                .isInstanceOf(AiWriteRefusedException.class);
            assertThatThrownBy(() -> afterRestart.startChangeSet(vaultId, EXTERN, "tom", "y.pdf"))
                .isInstanceOf(AiWriteRefusedException.class);
        }

        @Test
        void should_rejectUnsafePaths() {
            for (var path : List.of("../raus.md", ".obsidian/x.md", "ohne-endung", "a//b.md")) {
                assertThatThrownBy(() -> service.createNote(vaultId, newChangeSet().id(), path, "x", NoteLevel.of(1)), path)
                    .isInstanceOf(AiWriteRefusedException.class);
            }
        }

        // Ein Mensch tippt, waehrend die KI schreibt: beide Aenderungen muessen erhalten bleiben.
        @Test
        void should_keepAConcurrentHumanEdit() {
            var changeSet = newChangeSet();
            var created = service.createNote(vaultId, changeSet.id(), "A.md", "Absatz A\n\nAbsatz B\n", NoteLevel.of(1));
            var history = snapshots.listSince(created.noteId(), 0);
            // Mensch hat den Stand vor dem KI-Update gesehen und ergaenzt vorne:
            var humanUpdate = yjs.change(history.stream().map(r -> r.payload()).toList(), "Mensch: Absatz A\n\nAbsatz B\n").orElseThrow();

            service.updateNote(vaultId, changeSet.id(), created.noteId(), "Absatz A\n\nAbsatz B, von der KI\n");
            relay.saveIfCurrent(created.noteId(), snapshots.listSince(created.noteId(), 0).size(), humanUpdate);

            assertThat(service.readText(vaultId, created.noteId(), EXTERN)).isEqualTo("Mensch: Absatz A\n\nAbsatz B, von der KI\n");
        }
    }

    @Nested
    class Rueckgaengig {

        @Test
        void should_deleteCreatedNotes_andRestoreUpdatedOnes() {
            var own = service.startChangeSet(vaultId, EXTERN, "tom", "Vorher");
            var existing = service.createNote(vaultId, own.id(), "Bestand.md", "Bestand\n", NoteLevel.of(1));
            var changeSet = newChangeSet();
            var created = service.createNote(vaultId, changeSet.id(), "Neu.md", "neu\n", NoteLevel.of(1));
            service.updateNote(vaultId, changeSet.id(), existing.noteId(), "Bestand\nergänzt\n");

            var report = service.revert(vaultId, changeSet.id(), "tom");

            assertThat(report.reverted()).isEqualTo(2);
            assertThat(report.conflicts()).isEmpty();
            assertThat(notes.findById(vaultId, created.noteId())).isEmpty();
            assertThat(service.readText(vaultId, existing.noteId(), EXTERN)).isEqualTo("Bestand\n");
            assertThat(changeSets.find(vaultId, changeSet.id()).orElseThrow().revertedAt()).isNotNull();
        }

        // Rueckgaengig ist eine konfliktgepruefte Kompensation (ADR 0008): was ein Mensch seitdem
        // weitergeschrieben hat, wird nie ueberschrieben oder geloescht.
        @Test
        void should_leaveNotesAlone_thatSomeoneEditedAfterTheAi() {
            var changeSet = newChangeSet();
            var created = service.createNote(vaultId, changeSet.id(), "Neu.md", "neu\n", NoteLevel.of(1));
            humanEdit(created.noteId(), "neu\nvon Tom weitergeschrieben\n");

            var report = service.revert(vaultId, changeSet.id(), "tom");

            assertThat(report.reverted()).isZero();
            assertThat(report.conflicts()).extracting(AiRevertConflict::path).containsExactly("Neu.md");
            assertThat(service.readText(vaultId, created.noteId(), EXTERN)).isEqualTo("neu\nvon Tom weitergeschrieben\n");
        }

        @Test
        void should_revertOnlyOnce() {
            var changeSet = newChangeSet();
            service.createNote(vaultId, changeSet.id(), "Neu.md", "neu\n", NoteLevel.of(1));
            service.revert(vaultId, changeSet.id(), "tom");

            assertThatThrownBy(() -> service.revert(vaultId, changeSet.id(), "tom")).isInstanceOf(AiWriteRefusedException.class);
        }

        @Test
        void should_notFindChangeSetsOfOtherVaults() {
            var changeSet = newChangeSet();

            assertThatThrownBy(() -> service.revert(VaultId.newId(), changeSet.id(), "tom")).isInstanceOf(AiWriteRefusedException.class);
        }
    }
}
