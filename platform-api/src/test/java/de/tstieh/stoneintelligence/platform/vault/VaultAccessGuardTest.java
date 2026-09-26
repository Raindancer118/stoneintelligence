package de.tstieh.stoneintelligence.platform.vault;

import java.util.List;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultAccessGuardTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final VaultAccessGuard guard = new VaultAccessGuard(authorization, grants);
    private final VaultId vaultId = VaultId.newId();

    private void member(String subject, Permission... permissions) {
        var role = authorization.createRole(vaultId, subject + "-role", Set.of(permissions));
        var group = authorization.createGroup(vaultId, subject + "-group");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), subject);
    }

    @Test
    void should_throwForbidden_when_subjectHasNoPermissionInVault() {
        assertThatThrownBy(() -> guard.require(vaultId, "mallory", Permission.READ))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_pass_when_subjectHasRequiredPermissionViaGroupRole() {
        member("tom", Permission.READ);

        assertThatCode(() -> guard.require(vaultId, "tom", Permission.READ)).doesNotThrowAnyException();
    }

    @Test
    void should_throwForbidden_when_aFolderGrantTakesThePermissionAway() {
        member("tom", Permission.WRITE);
        grants.put(vaultId, GrantTarget.folder("private"), GrantScope.everyone(), Set.of(), "owner");

        assertThatThrownBy(() -> guard.require(vaultId, "tom", Permission.WRITE, "private/secret.md"))
            .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> guard.require(vaultId, "tom", Permission.WRITE, "notes/anything.md"))
            .doesNotThrowAnyException();
    }

    @Test
    void should_pass_when_aNoteGrantGivesMoreThanTheVaultRole() {
        member("ben", Permission.READ);
        var note = notes.create(vaultId, "Team/plan.md", NoteLevel.of(1), "tom");
        grants.put(vaultId, GrantTarget.entry(note.id(), note.path()), GrantScope.user("ben"), Set.of(Permission.READ, Permission.WRITE), "tom");

        assertThatCode(() -> guard.require(vaultId, "ben", Permission.WRITE, "Team/plan.md")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.require(vaultId, "ben", Permission.WRITE, "Team/other.md"))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_acceptMembersWithoutAnyVaultPermission_butNotOutsiders() {
        var group = authorization.createGroup(vaultId, "guests");
        authorization.addMember(group.id(), "guest");

        assertThatCode(() -> guard.requireMember(vaultId, "guest")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireMember(vaultId, "mallory")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_listOnlyReadableNotes_includingOnesSharedWithAGuest() {
        var group = authorization.createGroup(vaultId, "guests");
        authorization.addMember(group.id(), "guest");
        var shared = notes.create(vaultId, "shared.md", NoteLevel.of(1), "tom");
        var hidden = notes.create(vaultId, "hidden.md", NoteLevel.of(1), "tom");
        grants.put(vaultId, GrantTarget.entry(shared.id(), shared.path()), GrantScope.user("guest"), Set.of(Permission.READ), "tom");

        assertThat(guard.readableNotes(vaultId, "guest", List.of(shared, hidden))).containsExactly(shared);
        assertThat(guard.readablePaths(vaultId, "guest", List.of("shared.md", "hidden.md"), p -> p)).containsExactly("shared.md");
    }

    @Test
    void should_nameWhatSomeoneMayDoWithEachNote_andShowGrantsOnlyToManagers() {
        member("tom", Permission.READ, Permission.WRITE, Permission.MANAGE);
        member("ben", Permission.READ);
        var plain = notes.create(vaultId, "plain.md", NoteLevel.of(1), "tom");
        var shared = notes.create(vaultId, "Team/shared.md", NoteLevel.of(1), "tom");
        grants.put(vaultId, GrantTarget.folder("Team"), GrantScope.user("ben"), Set.of(Permission.READ, Permission.WRITE), "tom");

        var forBen = guard.entryAccess(vaultId, "ben", List.of(plain, shared));
        var forTom = guard.entryAccess(vaultId, "tom", List.of(plain, shared));

        assertThat(forBen.get(plain.id()).permissions()).containsExactly(Permission.READ);
        assertThat(forBen.get(shared.id()).permissions()).containsExactlyInAnyOrder(Permission.READ, Permission.WRITE);
        assertThat(forBen.get(shared.id()).shared()).isNull();
        assertThat(forTom.get(shared.id()).shared()).isTrue();
        assertThat(forTom.get(plain.id()).shared()).isFalse();
    }
}
