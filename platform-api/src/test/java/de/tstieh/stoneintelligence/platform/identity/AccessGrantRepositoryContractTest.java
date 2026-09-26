package de.tstieh.stoneintelligence.platform.identity;

import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vertrag, den {@code FakeAccessGrantRepository} und {@code JdbcAccessGrantRepository} beide erfuellen. */
public abstract class AccessGrantRepositoryContractTest {

    protected abstract AccessGrantRepository repository();

    protected abstract VaultId newVault();

    protected abstract NoteId newNote(VaultId vaultId, String path);

    protected abstract void renameNote(VaultId vaultId, NoteId noteId, String path);

    protected abstract void deleteNote(VaultId vaultId, NoteId noteId);

    @Test
    void should_storeFolderAndEntryGrants_withTheirPermissions() {
        var repository = repository();
        var vault = newVault();
        var note = newNote(vault, "Projects/plan.md");

        repository.put(vault, GrantTarget.folder("Projects"), GrantScope.user("ben"), Set.of(Permission.READ), "tom");
        repository.put(vault, GrantTarget.entry(note, "Projects/plan.md"), GrantScope.everyone(), null, "tom");

        assertThat(repository.list(vault))
            .extracting(AccessGrant::target, AccessGrant::scope, AccessGrant::permissions)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(GrantTarget.folder("Projects"), GrantScope.user("ben"), Set.of(Permission.READ)),
                org.assertj.core.groups.Tuple.tuple(GrantTarget.entry(note, "Projects/plan.md"), GrantScope.everyone(), null));
    }

    @Test
    void should_keepAnEmptySet_apartFrom_inheritingTheVault() {
        var repository = repository();
        var vault = newVault();

        repository.put(vault, GrantTarget.folder("Secret"), GrantScope.everyone(), Set.of(), "tom");

        var grant = repository.list(vault).getFirst();
        assertThat(grant.permissions()).isEmpty();
        assertThat(grant.inheritsVault()).isFalse();
    }

    @Test
    void should_replaceTheGrant_forTheSameTargetAndScope() {
        var repository = repository();
        var vault = newVault();
        var group = UUID.randomUUID();

        repository.put(vault, GrantTarget.folder("A"), GrantScope.group(group), Set.of(Permission.READ), "tom");
        repository.put(vault, GrantTarget.folder("/A/"), GrantScope.group(group), Set.of(Permission.WRITE), "tom");
        repository.put(vault, GrantTarget.folder("A"), GrantScope.everyone(), Set.of(), "tom");

        assertThat(repository.list(vault)).hasSize(2)
            .filteredOn(grant -> grant.scope().equals(GrantScope.group(group)))
            .singleElement().extracting(AccessGrant::permissions).isEqualTo(Set.of(Permission.WRITE));
    }

    @Test
    void should_scopeGrantsToTheirVault() {
        var repository = repository();
        var vault = newVault();
        var other = newVault();

        repository.put(vault, GrantTarget.folder("A"), GrantScope.everyone(), Set.of(), "tom");

        assertThat(repository.list(other)).isEmpty();
        assertThat(repository.remove(other, GrantTarget.folder("A"), GrantScope.everyone())).isFalse();
        assertThat(repository.list(vault)).hasSize(1);
    }

    @Test
    void should_removeOnlyTheNamedGrant() {
        var repository = repository();
        var vault = newVault();
        repository.put(vault, GrantTarget.folder("A"), GrantScope.user("ben"), Set.of(), "tom");
        repository.put(vault, GrantTarget.folder("A"), GrantScope.user("anna"), Set.of(), "tom");

        assertThat(repository.remove(vault, GrantTarget.folder("A"), GrantScope.user("ben"))).isTrue();
        assertThat(repository.remove(vault, GrantTarget.folder("A"), GrantScope.user("ben"))).isFalse();

        assertThat(repository.list(vault)).extracting(AccessGrant::scope).containsExactly(GrantScope.user("anna"));
    }

    @Test
    void should_followTheEntry_whenItIsRenamed_andVanishWithIt() {
        var repository = repository();
        var vault = newVault();
        var note = newNote(vault, "a.md");
        repository.put(vault, GrantTarget.entry(note, "a.md"), GrantScope.user("ben"), Set.of(), "tom");

        renameNote(vault, note, "Moved/b.md");
        assertThat(repository.list(vault)).extracting(AccessGrant::target)
            .containsExactly(GrantTarget.entry(note, "Moved/b.md"));

        deleteNote(vault, note);
        assertThat(repository.list(vault)).isEmpty();
    }

    @Test
    void should_moveFolderGrants_includingSubfolders_butNotSimilarlyNamedFolders() {
        var repository = repository();
        var vault = newVault();
        repository.put(vault, GrantTarget.folder("A"), GrantScope.everyone(), Set.of(), "tom");
        repository.put(vault, GrantTarget.folder("A/B"), GrantScope.everyone(), Set.of(), "tom");
        repository.put(vault, GrantTarget.folder("AB"), GrantScope.everyone(), Set.of(), "tom");

        repository.moveFolder(vault, "A", "X/A");

        assertThat(repository.list(vault)).extracting(grant -> ((GrantTarget.Folder) grant.target()).path())
            .containsExactlyInAnyOrder("X/A", "X/A/B", "AB");
    }

    @Test
    void should_removeFolderGrants_includingSubfolders() {
        var repository = repository();
        var vault = newVault();
        repository.put(vault, GrantTarget.folder("A"), GrantScope.everyone(), Set.of(), "tom");
        repository.put(vault, GrantTarget.folder("A/B"), GrantScope.everyone(), Set.of(), "tom");
        repository.put(vault, GrantTarget.folder("AB"), GrantScope.everyone(), Set.of(), "tom");

        repository.removeFolder(vault, "A");

        assertThat(repository.list(vault)).extracting(grant -> ((GrantTarget.Folder) grant.target()).path())
            .containsExactly("AB");
    }

    @Test
    void should_removeThePersonalGrantsOfASubject_andTheGrantsOfAGroup() {
        var repository = repository();
        var vault = newVault();
        var group = UUID.randomUUID();
        var note = newNote(vault, "a.md");
        repository.put(vault, GrantTarget.folder("A"), GrantScope.user("ben"), Set.of(), "tom");
        repository.put(vault, GrantTarget.entry(note, "a.md"), GrantScope.user("ben"), Set.of(), "tom");
        repository.put(vault, GrantTarget.folder("A"), GrantScope.group(group), Set.of(), "tom");
        repository.put(vault, GrantTarget.folder("A"), GrantScope.user("anna"), Set.of(), "tom");

        repository.removeSubject(vault, "ben");
        repository.removeGroup(vault, group);

        assertThat(repository.list(vault)).extracting(AccessGrant::scope).containsExactly(GrantScope.user("anna"));
    }
}
