package de.tstieh.stoneintelligence.platform.vault;

import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolderControllerTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final FakeFolderRepository folderRepository = new FakeFolderRepository();
    private final FolderController controller = new FolderController(
        new FolderRegistry(folderRepository, new VaultAnnouncementService(), grants), new VaultAccessGuard(authorization, grants));
    private final VaultId vaultId = VaultId.newId();

    @Test
    void should_listOneLevel_withoutFoldersSomeoneMayNotSee() {
        var role = authorization.createRole(vaultId, "lesen", Set.of(Permission.READ));
        var group = authorization.createGroup(vaultId, "alle");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "ben");
        folderRepository.ensure(vaultId, "Abteilungen/Vertrieb", "tom");
        folderRepository.ensure(vaultId, "Abteilungen/Personal", "tom");
        folderRepository.ensure(vaultId, "Wiki", "tom");
        grants.put(vaultId, GrantTarget.folder("Abteilungen/Personal"), GrantScope.user("ben"), Set.of(), "tom");

        var top = controller.children(vaultId.value().toString(), "", new TestingAuthenticationToken("ben", null));
        var below = controller.children(vaultId.value().toString(), "/Abteilungen/", new TestingAuthenticationToken("ben", null));

        assertThat(top).containsExactly(new FolderRepository.FolderChild("Abteilungen", true), new FolderRepository.FolderChild("Wiki", false));
        assertThat(below).containsExactly(new FolderRepository.FolderChild("Abteilungen/Vertrieb", false));
    }

    private void member(String subject, Set<Permission> permissions) {
        var role = authorization.createRole(vaultId, "rolle-" + subject, permissions);
        var group = authorization.createGroup(vaultId, "gruppe-" + subject);
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), subject);
    }

    @Test
    void should_refuseDeletingFolderTree_whenItContainsASubfolderTheCallerMayNotDelete() {
        member("ben", Set.of(Permission.READ, Permission.DELETE));
        folderRepository.ensure(vaultId, "Abteilungen/Personal", "tom");
        grants.put(vaultId, GrantTarget.folder("Abteilungen/Personal"), GrantScope.everyone(), Set.of(), "tom");

        assertThatThrownBy(() -> controller.delete(vaultId.value().toString(), "Abteilungen", new TestingAuthenticationToken("ben", null)))
            .isInstanceOf(ForbiddenException.class);

        assertThat(folderRepository.list(vaultId)).contains("Abteilungen/Personal");
        assertThat(grants.list(vaultId)).hasSize(1);
    }

    @Test
    void should_refuseDeletingFolderTree_whenItWouldDropGrantsTheCallerMayNotManage() {
        member("ben", Set.of(Permission.READ, Permission.DELETE));
        folderRepository.ensure(vaultId, "Abteilungen/Vertrieb", "tom");
        grants.put(vaultId, GrantTarget.folder("Abteilungen/Vertrieb"), GrantScope.user("ben"), Set.of(Permission.READ, Permission.DELETE), "tom");

        assertThatThrownBy(() -> controller.delete(vaultId.value().toString(), "Abteilungen", new TestingAuthenticationToken("ben", null)))
            .isInstanceOf(ForbiddenException.class);

        assertThat(grants.list(vaultId)).hasSize(1);
    }

    @Test
    void should_deleteFolderTreeWithItsGrants_whenCallerManagesEverythingInIt() {
        member("tom", java.util.EnumSet.allOf(Permission.class));
        folderRepository.ensure(vaultId, "Abteilungen/Personal", "tom");
        grants.put(vaultId, GrantTarget.folder("Abteilungen/Personal"), GrantScope.everyone(), Set.of(), "tom");

        controller.delete(vaultId.value().toString(), "Abteilungen", new TestingAuthenticationToken("tom", null));

        assertThat(folderRepository.list(vaultId)).isEmpty();
        assertThat(grants.list(vaultId)).isEmpty();
    }
}
