package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.FakeSnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRoomRegistry;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import de.tstieh.stoneintelligence.platform.vault.FakeFolderRepository;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.FolderRegistry;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkingServiceTest {

    private static YjsBridge yjs;

    @BeforeAll
    static void loadYjs() {
        yjs = YjsBridge.load();
    }

    @AfterAll
    static void closeYjs() {
        yjs.close();
    }

    private static final AiService LOKAL = new AiService("lokal", "Lokal", Set.of(1, 2));
    private final VaultId vaultId = VaultId.newId();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeSnapshotStore snapshots = new FakeSnapshotStore();
    private final SyncRelayService relay = new SyncRelayService(snapshots, new SyncRoomRegistry());
    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final VaultAccessGuard access = new VaultAccessGuard(authorization, grants);
    private final FakeAiChangeSetRepository changeSets = new FakeAiChangeSetRepository();
    private final FakeLinkingSettingsRepository settings = new FakeLinkingSettingsRepository();
    private final FakeAiJobRepository jobRepository = new FakeAiJobRepository();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-27T02:00:00Z"));
    private final AiServiceDirectory services = new AiServiceDirectory(List.of(LOKAL));
    private final AiWriteService ai = new AiWriteService(notes, snapshots, relay, yjs, new VaultAnnouncementService(),
        new FolderRegistry(new FakeFolderRepository(), new VaultAnnouncementService(), grants),
        (vault, note, actor, action, payload) -> { }, services, changeSets, now::get);
    private final AiJobService jobs = new AiJobService(jobRepository, () -> services,
        (vault, service, requestedBy, label) -> ai.startChangeSet(vault, service, requestedBy, label).id(),
        (vault, changeSet, actor) -> ai.revert(vault, changeSet, actor), now::get);
    private final FakeNoteEmbeddingRepository embeddings = new FakeNoteEmbeddingRepository(notes);
    private final LinkingService linking = new LinkingService(settings, jobs, ai, access, notes, services, embeddings, now::get);

    @BeforeEach
    void members() {
        member("tom", Set.of(Permission.READ, Permission.WRITE, Permission.CREATE, Permission.MANAGE));
        member("ben", Set.of(Permission.READ));
    }

    private void member(String subject, Set<Permission> permissions) {
        var role = authorization.createRole(vaultId, subject, permissions);
        var group = authorization.createGroup(vaultId, subject);
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), subject);
    }

    private NoteId note(String path, String text) {
        var note = notes.create(vaultId, path, NoteLevel.of(1), "tom");
        relay.saveIfCurrent(note.id(), 0, yjs.change(List.of(), text).orElseThrow());
        return note.id();
    }

    @Nested
    class Einstellungen {

        @Test
        void should_startOff_andLetOnlyManagersSwitchItOn_inTheirOwnName() {
            assertThat(linking.settings(vaultId, "ben").enabled()).isFalse();

            assertThatThrownBy(() -> linking.update(vaultId, "ben", new LinkingService.Change(true, true, null, null, null)))
                .isInstanceOf(ForbiddenException.class);
            var saved = linking.update(vaultId, "tom", new LinkingService.Change(true, true, 5, null, LinkingSettings.Mode.SEMANTIC));

            assertThat(saved.enabled()).isTrue();
            assertThat(saved.requestedBy()).isEqualTo("tom");
            assertThat(saved.maxLinksPerNote()).isEqualTo(5);
            assertThat(saved.service()).isEqualTo("lokal");
            assertThat(saved.mode()).isEqualTo(LinkingSettings.Mode.SEMANTIC);
            assertThat(linking.update(vaultId, "tom", new LinkingService.Change(true, true, 5, null, null)).mode())
                .as("no mode given keeps the mode").isEqualTo(LinkingSettings.Mode.SEMANTIC);
        }

        // Stufe 3 (KI-Pruefung) gibt es noch nicht - der Modus darf nicht so tun.
        @Test
        void should_refuseTheAiModeUntilItExists() {
            assertThatThrownBy(() -> linking.update(vaultId, "tom", new LinkingService.Change(true, true, null, null, LinkingSettings.Mode.AI)))
                .isInstanceOf(AiWriteRefusedException.class);
        }

        @Test
        void should_refuseAServiceThatDoesNotExist() {
            assertThatThrownBy(() -> linking.update(vaultId, "tom", new LinkingService.Change(true, true, null, "gibtsnicht", null)))
                .isInstanceOf(AiWriteRefusedException.class);
        }
    }

    @Nested
    class Laeufe {

        @Test
        void should_letSomeoneWhoMayWriteStartARun_inTheirName() {
            var run = linking.runNow(vaultId, "tom");

            assertThat(run.kind()).isEqualTo(AiJob.Kind.LINKING);
            assertThat(run.requestedBy()).isEqualTo("tom");
            assertThatThrownBy(() -> linking.runNow(vaultId, "ben")).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void should_startTheNightlyRun_onlyForEnabledVaults_whoseRequesterMayStillWrite() {
            linking.update(vaultId, "tom", new LinkingService.Change(true, true, null, null, null));
            var other = VaultId.newId();
            settings.save(new LinkingSettings(other, true, LinkingSettings.Mode.AI, true, null, "lokal", "weg", null, now.get()));

            var started = linking.startNightlyRuns();

            assertThat(started).isEqualTo(1);
            assertThat(jobRepository.list(vaultId, 10)).singleElement().extracting(AiJob::kind).isEqualTo(AiJob.Kind.LINKING);
            assertThat(jobRepository.list(other, 10)).isEmpty();
            assertThat(linking.startNightlyRuns()).as("a run is still open").isZero();
        }

        @Test
        void should_rememberWhenARunFinished() {
            var run = linking.runNow(vaultId, "tom");
            jobs.claim();

            linking.finished(run.id());

            assertThat(linking.settings(vaultId, "tom").lastRunAt()).isEqualTo(now.get());
        }
    }

    @Nested
    class Verlinken {

        @Test
        void should_setLinksWithTheRequestersRights() {
            var target = note("Licht.md", "# Licht\n");
            var source = note("Pflanzen.md", "Pflanzen brauchen Licht.\n");
            var run = linking.runNow(vaultId, "tom");
            var changeSet = jobs.claim().orElseThrow().changeSetId();

            var applied = linking.link(vaultId, changeSet, source, List.of(new AiWriteService.LinkRequest(target, "Licht", false)));

            assertThat(applied).hasSize(1);
            assertThat(ai.readText(vaultId, source, LOKAL)).isEqualTo("Pflanzen brauchen [[Licht]].\n");
            assertThat(run.id()).isNotNull();
        }

        @Test
        void should_neitherWriteWhereTheRequesterMayNot_norRevealTargetsTheyCannotRead() {
            var target = note("Privat/Licht.md", "# Licht\n");
            var source = note("Pflanzen.md", "Pflanzen brauchen Licht.\n");
            linking.runNow(vaultId, "tom");
            var changeSet = jobs.claim().orElseThrow().changeSetId();
            grants.put(vaultId, GrantTarget.folder("Privat"), GrantScope.user("tom"), Set.of(), "tom");

            assertThatThrownBy(() -> linking.link(vaultId, changeSet, source, List.of(new AiWriteService.LinkRequest(target, "Licht", false))))
                .isInstanceOf(ForbiddenException.class);
            grants.put(vaultId, GrantTarget.entry(source, "Pflanzen.md"), GrantScope.user("tom"), Set.of(Permission.READ), "tom");
            assertThatThrownBy(() -> linking.link(vaultId, changeSet, source, List.of()))
                .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class Aehnlichkeit {

        private float[] vector(int axis, double tilt) {
            return NoteEmbeddingRepositoryContractTest.vector(axis, tilt);
        }

        private java.util.UUID run() {
            linking.runNow(vaultId, "tom");
            return jobs.claim().orElseThrow().changeSetId();
        }

        @Test
        void should_storeVectors_andReportWhatIsIndexed_onlyForNotesTheRunMaySee() {
            var visible = note("Licht.md", "# Licht\n");
            var hidden = note("Privat/Tagebuch.md", "# Geheim\n");
            grants.put(vaultId, GrantTarget.folder("Privat"), GrantScope.user("tom"), Set.of(), "tom");
            var changeSet = run();

            linking.storeEmbeddings(vaultId, changeSet, visible, "m", "h1",
                List.of(new NoteEmbeddingRepository.Chunk(0, "Licht", vector(3, 0))));

            assertThat(linking.embeddingStates(vaultId, changeSet)).extracting(NoteEmbeddingRepository.State::noteId).containsExactly(visible);
            assertThatThrownBy(() -> linking.storeEmbeddings(vaultId, changeSet, hidden, "m", "h",
                List.of(new NoteEmbeddingRepository.Chunk(0, null, vector(3, 0))))).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void should_refuseVectorsOfTheWrongSize_orTooMany() {
            var note = note("Licht.md", "# Licht\n");
            var changeSet = run();

            assertThatThrownBy(() -> linking.storeEmbeddings(vaultId, changeSet, note, "m", "h",
                List.of(new NoteEmbeddingRepository.Chunk(0, null, new float[3])))).isInstanceOf(AiWriteRefusedException.class);
            var tooMany = java.util.stream.IntStream.range(0, 501).mapToObj(i -> new NoteEmbeddingRepository.Chunk(i, null, vector(3, 0))).toList();
            assertThatThrownBy(() -> linking.storeEmbeddings(vaultId, changeSet, note, "m", "h", tooMany))
                .isInstanceOf(AiWriteRefusedException.class);
        }

        @Test
        void should_showSimilarNotes_onlyAmongThoseTheReaderMaySee() {
            var source = note("Pflanzen.md", "x\n");
            var close = note("Photosynthese.md", "x\n");
            var secret = note("Privat/Gewaechshaus.md", "x\n");
            var changeSet = run();
            linking.storeEmbeddings(vaultId, changeSet, source, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, null, vector(5, 0))));
            linking.storeEmbeddings(vaultId, changeSet, close, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, "Licht", vector(5, 0.2))));
            linking.storeEmbeddings(vaultId, changeSet, secret, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, null, vector(5, 0.1))));
            grants.put(vaultId, GrantTarget.folder("Privat"), GrantScope.user("ben"), Set.of(), "tom");

            var forBen = linking.similarNotes(vaultId, "ben", source, 10);
            var forTom = linking.similarNotes(vaultId, "tom", source, 10);

            assertThat(forBen).extracting(LinkingService.SimilarNote::path).containsExactly("Photosynthese.md");
            assertThat(forTom).extracting(LinkingService.SimilarNote::path).containsExactly("Privat/Gewaechshaus.md", "Photosynthese.md");
            assertThat(forBen.getFirst().heading()).isEqualTo("Licht");
            assertThatThrownBy(() -> linking.similarNotes(vaultId, "mallory", source, 10)).isInstanceOf(ForbiddenException.class);
        }
    }
}
