package de.tstieh.stoneintelligence.platform.vault.feed;

import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.files.FakeFileVersionRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.FakeSnapshotStore;
import de.tstieh.stoneintelligence.platform.vault.EntryListing;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.NoteController;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeControllerTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final FakeSnapshotStore snapshots = new FakeSnapshotStore();
    private final FakeChangeFeed feed = new FakeChangeFeed();
    private final VaultAccessGuard access = new VaultAccessGuard(authorization, grants);
    private final ChangeController controller = new ChangeController(feed, notes, access,
        new EntryListing(snapshots, new FakeFileVersionRepository(notes), access));
    private final VaultId vaultId = VaultId.newId();
    private java.util.UUID team;

    @BeforeEach
    void setUp() {
        var role = authorization.createRole(vaultId, "schreiben", Set.of(Permission.READ, Permission.WRITE));
        var group = authorization.createGroup(vaultId, "Team");
        team = group.id();
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "ben");
        authorization.addMember(group.id(), "anna");
    }

    private ChangeController.Changes changes(String since) {
        return controller.changes(vaultId.value().toString(), since, 500, "note,file", new TestingAuthenticationToken("ben", null));
    }

    private Note note(String path) {
        var note = notes.create(vaultId, path, NoteLevel.of(1), "tom");
        feed.changed(note.id());
        return note;
    }

    @Test
    void should_startWithACursorAndNothingElse() {
        var start = changes(null);

        assertThat(start.cursor()).isEqualTo(Long.toString(feed.now()));
        assertThat(start.entries()).isEmpty();
        assertThat(start.more()).isFalse();
    }

    @Test
    void should_deliverWhatChangedSinceTheCursor_withRevisionAndRights() {
        var cursor = changes(null).cursor();
        var plan = note("Team/plan.md");
        snapshots.append(plan.id(), new byte[] {1}, false, "anna");
        feed.changed(plan.id());

        var delta = changes(cursor);

        assertThat(delta.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.path()).isEqualTo("Team/plan.md");
            assertThat(entry.revision()).isEqualTo(1);
            assertThat(entry.permissions()).contains(Permission.WRITE);
        });
        assertThat(changes(delta.cursor()).entries()).isEmpty();
    }

    @Test
    void should_reportDeletedAndNoLongerReadableEntries_asRemoved() {
        var cursor = changes(null).cursor();
        var gone = NoteId.newId();
        feed.changed(gone);
        var secret = note("Geheim/x.md");
        grants.put(vaultId, GrantTarget.folder("Geheim"), GrantScope.user("ben"), Set.of(), "tom");

        var delta = changes(cursor);

        assertThat(delta.entries()).isEmpty();
        assertThat(delta.removed()).containsExactlyInAnyOrder(gone.value().toString(), secret.id().value().toString());
    }

    @Test
    void should_pageThroughManyChanges_withFoldersAndAccessOnlyOnTheLastPage() {
        var cursor = changes(null).cursor();
        for (var i = 0; i < 5; i++) {
            note("Team/n" + i + ".md");
        }
        feed.folderChanged("Team");

        var first = controller.changes(vaultId.value().toString(), cursor, 2, "note,file", new TestingAuthenticationToken("ben", null));
        assertThat(first.more()).isTrue();
        assertThat(first.folders().changed()).isEmpty();
        var seen = new java.util.ArrayList<>(first.entries().stream().map(NoteController.ListedNoteResponse::path).toList());
        var next = first;
        while (next.more()) {
            next = controller.changes(vaultId.value().toString(), next.cursor(), 2, "note,file", new TestingAuthenticationToken("ben", null));
            next.entries().forEach(entry -> seen.add(entry.path()));
        }

        assertThat(seen).containsExactlyInAnyOrder("Team/n0.md", "Team/n1.md", "Team/n2.md", "Team/n3.md", "Team/n4.md");
        assertThat(next.folders().changed()).containsExactly("Team");
    }

    @Test
    void should_askToRelist_onlyWhereThisPersonsViewChanged() {
        var cursor = changes(null).cursor();
        feed.access(new ChangeFeed.AccessEvent("USER", "anna", "Privat", null));
        feed.access(new ChangeFeed.AccessEvent("GROUP", team.toString(), "Kunden", null));
        feed.access(new ChangeFeed.AccessEvent("GROUP", java.util.UUID.randomUUID().toString(), "Fremd", null));
        feed.access(new ChangeFeed.AccessEvent("EVERYONE", null, "Kunden/Archiv", null));
        feed.access(new ChangeFeed.AccessEvent("USER", "ben", "Projekte", null));

        assertThat(changes(cursor).relist()).containsExactlyInAnyOrder("Kunden", "Projekte");

        feed.access(new ChangeFeed.AccessEvent("USER", "ben", "", null));
        assertThat(changes(cursor).relist()).containsExactly("");
    }

    @Test
    void should_deliverANoteWhoseOwnSharingChanged() {
        var cursor = changes(null).cursor();
        var plan = notes.create(vaultId, "Team/plan.md", NoteLevel.of(1), "tom");
        feed.access(new ChangeFeed.AccessEvent("USER", "ben", null, plan.id()));

        assertThat(changes(cursor).entries()).extracting(NoteController.ListedNoteResponse::path).containsExactly("Team/plan.md");
    }

    @Test
    void should_hideFoldersSomeoneMayNotSee_andLeaveOutFilesForOldClients() {
        var cursor = changes(null).cursor();
        grants.put(vaultId, GrantTarget.folder("Geheim"), GrantScope.user("ben"), Set.of(), "tom");
        feed.folderChanged("Geheim");
        feed.folderChanged("Team");
        var file = notes.create(vaultId, "Team/bild.png", NoteLevel.of(1), "tom", NoteKind.FILE);
        feed.changed(file.id());

        var delta = controller.changes(vaultId.value().toString(), cursor, 500, "note", new TestingAuthenticationToken("ben", null));

        assertThat(delta.folders().changed()).containsExactly("Team");
        assertThat(delta.entries()).isEmpty();
        assertThat(delta.removed()).isEmpty();
    }

    @Test
    void should_refuseStrangersAndBrokenCursors() {
        assertThatThrownBy(() -> controller.changes(vaultId.value().toString(), null, 500, "note", new TestingAuthenticationToken("eve", null)))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> changes("nonsense"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
