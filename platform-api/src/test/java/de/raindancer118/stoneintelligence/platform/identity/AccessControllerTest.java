package de.raindancer118.stoneintelligence.platform.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultSubscriber;
import de.raindancer118.stoneintelligence.platform.vault.FakeFolderRepository;
import de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository;
import de.raindancer118.stoneintelligence.platform.vault.ForbiddenException;
import de.raindancer118.stoneintelligence.platform.vault.Note;
import de.raindancer118.stoneintelligence.platform.vault.NoteNotFoundException;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import static de.raindancer118.stoneintelligence.platform.identity.Permission.CREATE;
import static de.raindancer118.stoneintelligence.platform.identity.Permission.DELETE;
import static de.raindancer118.stoneintelligence.platform.identity.Permission.MANAGE;
import static de.raindancer118.stoneintelligence.platform.identity.Permission.READ;
import static de.raindancer118.stoneintelligence.platform.identity.Permission.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessControllerTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeFolderRepository folders = new FakeFolderRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final VaultAccessGuard guard = new VaultAccessGuard(authorization, grants);
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final List<String> audit = new ArrayList<>();
    private final AccessController controller = new AccessController(guard, grants, authorization, notes, folders,
        (vault, note, actor, action, payload) -> audit.add(actor + " " + action + " " + payload), announcements);

    private final VaultId vaultId = VaultId.newId();
    private final AtomicInteger accessChanges = new AtomicInteger();
    private Group owners;
    private Group readers;
    private Note plan;

    @BeforeEach
    void setUp() {
        owners = group("owners", Set.of(READ, WRITE, CREATE, DELETE, MANAGE), "tom");
        readers = group("readers", Set.of(READ), "ben", "cleo");
        plan = notes.create(vaultId, "Team/plan.md", NoteLevel.of(1), "tom");
        folders.ensure(vaultId, "Team", "tom");
        announcements.subscribe(vaultId, new VaultSubscriber() {
            @Override
            public String id() {
                return "listener";
            }

            @Override
            public boolean mayRead(String path) {
                return true;
            }

            @Override
            public void sendVaultEvent(byte messageType, NoteId noteId, String path) {
            }

            @Override
            public void accessChanged() {
                accessChanges.incrementAndGet();
            }
        });
    }

    private Group group(String name, Set<Permission> permissions, String... members) {
        var role = authorization.createRole(vaultId, name + "-role", permissions);
        var group = authorization.createGroup(vaultId, name);
        authorization.assignRole(group.id(), role.id());
        for (var member : members) {
            authorization.addMember(group.id(), member);
        }
        return group;
    }

    private static Authentication as(String subject) {
        return new TestingAuthenticationToken(subject, null);
    }

    private String vault() {
        return vaultId.value().toString();
    }

    private String planId() {
        return plan.id().value().toString();
    }

    private static AccessController.GrantRequest user(String subject, Permission... permissions) {
        return new AccessController.GrantRequest("USER", subject, List.of(permissions));
    }

    @Nested
    class Report {

        @Test
        void should_showManagersWhoHasAccess_andWhereItComesFrom() {
            controller.putFolderGrant(vault(), "Team", user("ben"), as("tom"));
            controller.putNoteGrant(vault(), planId(), user("cleo", READ, WRITE), as("tom"));

            var report = controller.noteAccess(vault(), planId(), as("tom"));

            assertThat(report.target().path()).isEqualTo("Team/plan.md");
            assertThat(report.grants()).singleElement().satisfies(grant -> {
                assertThat(grant.subject()).isEqualTo("cleo");
                assertThat(grant.permissions()).containsExactlyInAnyOrder(READ, WRITE);
            });
            assertThat(report.inherited()).singleElement().satisfies(grant -> assertThat(grant.target().path()).isEqualTo("Team"));
            assertThat(report.members()).extracting(AccessController.MemberAccess::subject).containsExactlyInAnyOrder("tom", "ben", "cleo");
            assertThat(report.members()).filteredOn(member -> member.subject().equals("ben")).singleElement().satisfies(ben -> {
                assertThat(ben.permissions()).isEmpty();
                assertThat(ben.source().target().kind()).isEqualTo("folder");
            });
            assertThat(report.members()).filteredOn(member -> member.subject().equals("tom")).singleElement()
                .satisfies(tom -> assertThat(tom.source()).isNull());
        }

        @Test
        void should_showOthersOnlyTheirOwnAccess() {
            var report = controller.noteAccess(vault(), planId(), as("ben"));

            assertThat(report.mine().permissions()).containsExactly(READ);
            assertThat(report.grants()).isEmpty();
            assertThat(report.members()).isEmpty();
        }

        @Test
        void should_refuse_whoMayNotEvenReadTheEntry() {
            controller.putNoteGrant(vault(), planId(), user("ben"), as("tom"));

            assertThatThrownBy(() -> controller.noteAccess(vault(), planId(), as("ben"))).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> controller.noteAccess(vault(), planId(), as("mallory"))).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void should_reportAFolder_andTheWholeVault() {
            var team = controller.folderAccess(vault(), "Team/", as("tom"));
            var root = controller.folderAccess(vault(), "", as("tom"));

            assertThat(team.target()).isEqualTo(new AccessController.TargetResponse("folder", "Team", null));
            assertThat(root.target().path()).isEmpty();
        }

        @Test
        void should_answer404_forAnEntryOfAnotherVault() {
            var foreign = notes.create(VaultId.newId(), "x.md", NoteLevel.of(1), "eve");

            assertThatThrownBy(() -> controller.noteAccess(vault(), foreign.id().value().toString(), as("tom")))
                .isInstanceOf(NoteNotFoundException.class);
        }
    }

    @Nested
    class Changing {

        @Test
        void should_letAReaderEdit_exactlyOneNote_andRecordIt() {
            controller.putNoteGrant(vault(), planId(), user("ben", READ, WRITE), as("tom"));

            assertThat(guard.accessAt(vaultId, "ben", "Team/plan.md").allows(WRITE)).isTrue();
            assertThat(guard.accessAt(vaultId, "ben", "Team/other.md").allows(WRITE)).isFalse();
            assertThat(audit).singleElement().asString().startsWith("tom ACCESS_GRANTED");
            assertThat(accessChanges).hasValue(1);
        }

        @Test
        void should_hideAFolder_andShowItAgain() {
            controller.putFolderGrant(vault(), "Team", user("ben"), as("tom"));
            assertThat(guard.accessAt(vaultId, "ben", "Team/plan.md").allows(READ)).isFalse();

            controller.removeFolderGrant(vault(), "Team", "USER", "ben", as("tom"));

            assertThat(guard.accessAt(vaultId, "ben", "Team/plan.md").allows(READ)).isTrue();
            assertThat(audit).last().asString().startsWith("tom ACCESS_REVOKED");
            assertThat(accessChanges).hasValue(2);
        }

        @Test
        void should_acceptGroups_everyone_andInheritingTheVault() {
            controller.putFolderGrant(vault(), "Team", new AccessController.GrantRequest("GROUP", readers.id().toString(), List.of()), as("tom"));
            controller.putNoteGrant(vault(), planId(), new AccessController.GrantRequest("EVERYONE", null, null), as("tom"));

            assertThat(guard.accessAt(vaultId, "ben", "Team/other.md").allows(READ)).isFalse();
            assertThat(guard.accessAt(vaultId, "ben", "Team/plan.md").permissions()).containsExactly(READ);
        }

        @Test
        void should_onlyAcceptMembers_andGroupsOfThisVault() {
            var foreignGroup = authorization.createGroup(VaultId.newId(), "foreign");

            assertThatThrownBy(() -> controller.putNoteGrant(vault(), planId(), user("stranger", READ), as("tom")))
                .isInstanceOf(InvalidGrantException.class);
            assertThatThrownBy(() -> controller.putNoteGrant(vault(), planId(),
                new AccessController.GrantRequest("GROUP", foreignGroup.id().toString(), List.of()), as("tom")))
                .isInstanceOf(InvalidGrantException.class);
            assertThatThrownBy(() -> controller.putNoteGrant(vault(), planId(),
                new AccessController.GrantRequest("NOBODY", "x", List.of()), as("tom")))
                .isInstanceOf(InvalidGrantException.class);
            assertThat(grants.list(vaultId)).isEmpty();
        }

        @Test
        void should_refuseAFolderThatDoesNotExist() {
            assertThatThrownBy(() -> controller.putFolderGrant(vault(), "Nowhere", user("ben"), as("tom")))
                .isInstanceOf(de.raindancer118.stoneintelligence.platform.vault.FolderNotFoundException.class);
        }

        @Test
        void should_requireManageOnTheTarget() {
            assertThatThrownBy(() -> controller.putNoteGrant(vault(), planId(), user("cleo"), as("ben")))
                .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void should_letSomeoneManageOneFolder_butNotHandOutMoreThanTheyHave() {
            controller.putFolderGrant(vault(), "Team", user("ben", READ, MANAGE), as("tom"));

            controller.putNoteGrant(vault(), planId(), user("cleo"), as("ben"));
            assertThatThrownBy(() -> controller.putNoteGrant(vault(), planId(), user("cleo", READ, WRITE), as("ben")))
                .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> controller.putFolderGrant(vault(), "", user("cleo"), as("ben")))
                .isInstanceOf(ForbiddenException.class);
        }
    }
}
