package de.tstieh.stoneintelligence.platform.vault;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.files.FakeFileVersionRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.FakeSnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRoomRegistry;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultSubscriber;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class NoteControllerTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final FakeSnapshotStore snapshots = new FakeSnapshotStore();
    private final de.tstieh.stoneintelligence.platform.search.FakeNoteTextIndex texts = new de.tstieh.stoneintelligence.platform.search.FakeNoteTextIndex();
    private final NoteController controller = new NoteController(notes,
        new SyncRelayService(snapshots, new SyncRoomRegistry()), (vault, note, actor, action, payload) -> { },
        new VaultAccessGuard(authorization, grants), announcements, snapshots,
        new FolderRegistry(new FakeFolderRepository(), announcements, grants), new FakeFileVersionRepository(notes), texts);
    private final VaultId vaultId = VaultId.newId();
    private final AtomicInteger accessChecks = new AtomicInteger();

    NoteControllerTest() {
        var role = authorization.createRole(vaultId, "alles", EnumSet.allOf(Permission.class));
        var group = authorization.createGroup(vaultId, "alle");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "tom");
        announcements.subscribe(vaultId, new VaultSubscriber() {
            @Override public String id() { return "geraet"; }
            @Override public boolean mayRead(String path) { return true; }
            @Override public void sendVaultEvent(byte messageType, NoteId noteId, String path) { }
            @Override public void accessChanged() { accessChecks.incrementAndGet(); }
        });
    }

    private NoteController.NoteResponse rename(String from, String to) {
        var note = notes.create(vaultId, from, NoteLevel.of(1), "tom");
        return controller.rename(vaultId.value().toString(), note.id().value().toString(),
            new NoteController.RenameNoteRequest(to), new TestingAuthenticationToken("tom", null));
    }

    // Wer die Notiz offen hat, darf sie nach dem Verschieben in einen gesperrten Ordner nicht weiter live bekommen.
    @Test
    void should_recheckOpenConnections_whenANoteMovesToAnotherFolder() {
        rename("Offen/A.md", "Privat/A.md");

        assertThat(accessChecks).hasValue(1);
    }

    @Test
    void should_notRecheckConnections_whenANoteIsOnlyRenamedInPlace() {
        rename("Offen/A.md", "Offen/B.md");

        assertThat(accessChecks).hasValue(0);
    }

    @Test
    void should_capThePageSize_whenListingTheWholeVault() {
        for (var i = 0; i < 1001; i++) {
            notes.create(vaultId, "N" + i + ".md", NoteLevel.of(1), "tom");
        }

        var page = controller.reconcile(vaultId.value().toString(), null, 100_000, "note", null, null,
            new TestingAuthenticationToken("tom", null));

        assertThat(page.notes()).hasSize(1000);
        assertThat(page.complete()).isFalse();
    }

    @Test
    void should_returnAtLeastOneEntry_whenThePageSizeIsNotPositive() {
        notes.create(vaultId, "A.md", NoteLevel.of(1), "tom");
        notes.create(vaultId, "B.md", NoteLevel.of(1), "tom");

        var page = controller.reconcile(vaultId.value().toString(), null, -3, "note", null, null,
            new TestingAuthenticationToken("tom", null));

        assertThat(page.notes()).hasSize(1);
    }

    private NoteController.NoteResponse create(String path, String kind) {
        return controller.create(vaultId.value().toString(), new NoteController.CreateNoteRequest(path, 1, kind),
            new TestingAuthenticationToken("tom", null));
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertBadRequest(
        org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        return org.assertj.core.api.Assertions.assertThatThrownBy(call)
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }

    // ADR 0018: Canvas sind Eintraege eigener Art - nur mit .canvas-Pfad, nur auf Anfrage in Listen.
    @Test
    void should_createACanvas_asItsOwnKind_andAnnounceItWithThatKind() {
        var received = new java.util.ArrayList<String>();
        announcements.subscribe(vaultId, new VaultSubscriber() {
            @Override public String id() { return "neu"; }
            @Override public boolean mayRead(String path) { return true; }
            @Override public boolean wantsFileEvents() { return true; }
            @Override public void sendVaultEvent(byte messageType, NoteId noteId, String path) { received.add(path); }
            @Override public void accessChanged() { }
        });

        var canvas = create("Ideen/Board.canvas", "canvas");

        assertThat(canvas.kind()).isEqualTo(NoteKind.CANVAS);
        assertThat(received).containsExactly("Ideen/Board.canvas");
    }

    @Test
    void should_keepCanvasAndMarkdownPathsApart() {
        assertBadRequest(() -> create("Board.canvas", null));
        assertBadRequest(() -> create("Notiz.md", "canvas"));
        assertBadRequest(() -> create(".canvas", "canvas"));
        assertBadRequest(() -> create("Board.canvas", "board"));
        assertThat(create("Notiz.md", "note").kind()).isEqualTo(NoteKind.NOTE);
    }

    @Test
    void should_keepACanvasACanvas_whenRenamed() {
        var canvas = create("Board.canvas", "canvas");
        var id = canvas.id();
        var auth = new TestingAuthenticationToken("tom", null);

        assertBadRequest(() -> controller.rename(vaultId.value().toString(), id, new NoteController.RenameNoteRequest("Board.md"), auth));
        assertThat(controller.rename(vaultId.value().toString(), id, new NoteController.RenameNoteRequest("Neu/Board.canvas"), auth).path())
            .isEqualTo("Neu/Board.canvas");
    }

    @Test
    void should_listCanvasesOnlyWhenAskedFor() {
        create("Board.canvas", "canvas");
        create("Notiz.md", null);
        var auth = new TestingAuthenticationToken("tom", null);

        var plain = controller.reconcile(vaultId.value().toString(), null, 100, "note,file", null, null, auth);
        var withCanvas = controller.reconcile(vaultId.value().toString(), null, 100, "note,file,canvas", null, null, auth);

        assertThat(plain.notes()).extracting(NoteController.ListedNoteResponse::path).containsExactly("Notiz.md");
        assertThat(withCanvas.notes()).extracting(NoteController.ListedNoteResponse::path).containsExactlyInAnyOrder("Notiz.md", "Board.canvas");
    }

    private NoteController.SearchResponse search(String query, String actor) {
        return controller.search(vaultId.value().toString(), query, 50, "note,file", new TestingAuthenticationToken(actor, null));
    }

    private de.tstieh.stoneintelligence.domain.notelevel.NoteLevel level() {
        return NoteLevel.of(1);
    }

    @Test
    void should_findNotesByTheirContent_withTheMatchingPassage() {
        var plan = notes.create(vaultId, "Projekte/Plan.md", level(), "tom");
        texts.upsert(plan.id(), vaultId, 1, "Die Bestellmengen werden nach Andler berechnet");
        notes.create(vaultId, "Projekte/Andere.md", level(), "tom");

        var found = search("bestell", "tom");

        assertThat(found.notes()).extracting(NoteController.ListedNoteResponse::path).containsExactly("Projekte/Plan.md");
        assertThat(found.excerpts().get(plan.id().value().toString()))
            .contains(de.tstieh.stoneintelligence.platform.search.NoteTextIndex.MARK_START + "Bestellmengen");
    }

    @Test
    void should_listPathMatchesFirst_andEachNoteOnce() {
        var titled = notes.create(vaultId, "Logistik/Bestellung.md", level(), "tom");
        texts.upsert(titled.id(), vaultId, 1, "Bestellung bei Lieferant A");
        var content = notes.create(vaultId, "Logistik/Ablauf.md", level(), "tom");
        texts.upsert(content.id(), vaultId, 1, "Erst die Bestellung pruefen");

        var found = search("bestellung", "tom");

        assertThat(found.notes()).extracting(NoteController.ListedNoteResponse::path)
            .containsExactly("Logistik/Bestellung.md", "Logistik/Ablauf.md");
    }

    // Inhaltstreffer laufen durch dieselbe Rechtepruefung - sonst verriete die Suche gesperrte Texte.
    @Test
    void should_hideContentMatches_thatThePersonMayNotRead() {
        authorization.addMember(authorization.listGroups(vaultId).getFirst().id(), "anna");
        grants.put(vaultId, de.tstieh.stoneintelligence.platform.identity.GrantTarget.folder("Privat"),
            de.tstieh.stoneintelligence.platform.identity.GrantScope.user("anna"), java.util.Set.of(), "tom");
        var secret = notes.create(vaultId, "Privat/Gehalt.md", level(), "tom");
        texts.upsert(secret.id(), vaultId, 1, "Gehaltsband fuer Anna");

        var found = search("gehalt", "anna");

        assertThat(found.notes()).isEmpty();
        assertThat(found.excerpts()).isEmpty();
    }
}
