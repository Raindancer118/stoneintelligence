package de.raindancer118.stoneintelligence.platform.identity;

import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.vault.ForbiddenException;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationControllerTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository notes =
        new de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final VaultAccessGuard guard = new VaultAccessGuard(authorization, grants);
    private final java.util.List<String> audit = new java.util.ArrayList<>();
    private final AuthorizationController controller = new AuthorizationController(authorization, guard, grants, notes,
        (vault, note, actor, action, payload) -> audit.add(action),
        new de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService());

    private VaultId ownerBootstrappedVault(String owner) {
        var vaultId = VaultId.newId();
        var role = authorization.createRole(vaultId, "owner",
            java.util.EnumSet.allOf(Permission.class));
        var group = authorization.createGroup(vaultId, "owners");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), owner);
        return vaultId;
    }

    @Test
    void should_createAndListRole_when_actorHasDeletePermission() {
        var vaultId = ownerBootstrappedVault("tom");
        var auth = new TestingAuthenticationToken("tom", null);

        var created = controller.createRole(vaultId.value().toString(),
            new AuthorizationController.CreateRoleRequest("editor", List.of(Permission.READ, Permission.WRITE)), auth);

        assertThat(created.name()).isEqualTo("editor");
        assertThat(controller.listRoles(vaultId.value().toString(), auth))
            .extracting(AuthorizationController.RoleResponse::name).contains("editor");
    }

    @Test
    void should_rejectRoleCreation_when_actorLacksPermission() {
        var vaultId = ownerBootstrappedVault("tom");
        var mallory = new TestingAuthenticationToken("mallory", null);

        assertThatThrownBy(() -> controller.createRole(vaultId.value().toString(),
            new AuthorizationController.CreateRoleRequest("editor", List.of(Permission.READ)), mallory))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_createGroupAddMemberAndAssignRole_when_actorHasDeletePermission() {
        var vaultId = ownerBootstrappedVault("tom");
        var auth = new TestingAuthenticationToken("tom", null);
        var role = controller.createRole(vaultId.value().toString(),
            new AuthorizationController.CreateRoleRequest("reader", List.of(Permission.READ)), auth);
        var group = controller.createGroup(vaultId.value().toString(),
            new AuthorizationController.CreateGroupRequest("readers"), auth);

        controller.addMember(vaultId.value().toString(), java.util.UUID.fromString(group.id()),
            new AuthorizationController.MemberRequest("alice"), auth);
        controller.assignRole(vaultId.value().toString(), java.util.UUID.fromString(group.id()),
            java.util.UUID.fromString(role.id()), auth);

        var groups = controller.listGroups(vaultId.value().toString(), auth);
        var readers = groups.stream().filter(g -> g.name().equals("readers")).findFirst().orElseThrow();
        assertThat(readers.memberSubjects()).containsExactly("alice");
        assertThat(readers.roleIds()).containsExactly(role.id());
    }

    /**
     * Notizen loeschen und Mitglieder verwalten waren bisher dasselbe Recht (DELETE) - damit
     * haette jede Person, die man zum Mitarbeiten einlaedt, selbst weitere einladen und die
     * Rechte aller anderen aendern koennen.
     */
    @Test
    void should_rejectMemberManagement_forCollaboratorsWhoMayOnlyDeleteNotes() {
        var vaultId = ownerBootstrappedVault("tom");
        var editorRole = authorization.createRole(vaultId, "Mitbearbeiter",
            java.util.EnumSet.of(Permission.READ, Permission.WRITE, Permission.CREATE, Permission.DELETE));
        var editors = authorization.createGroup(vaultId, "Mitbearbeiter");
        authorization.assignRole(editors.id(), editorRole.id());
        authorization.addMember(editors.id(), "carol");
        var carol = new TestingAuthenticationToken("carol", null);

        assertThatThrownBy(() -> controller.addMember(vaultId.value().toString(), editors.id(),
            new AuthorizationController.MemberRequest("mallory"), carol))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.createRole(vaultId.value().toString(),
            new AuthorizationController.CreateRoleRequest("x", List.of(Permission.READ)), carol))
            .isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"addMember", "removeMember", "assignRole", "unassignRole"})
    void should_rejectGroupMutation_when_groupBelongsToAnotherVault(String operation) {
        var actorVault = ownerBootstrappedVault("mallory");
        var victimVault = ownerBootstrappedVault("tom");
        var victimGroup = authorization.listGroups(victimVault).getFirst();
        var victimRole = authorization.listRoles(victimVault).getFirst();
        var auth = new TestingAuthenticationToken("mallory", null);
        var pathVault = actorVault.value().toString();

        assertThatThrownBy(() -> {
            switch (operation) {
                case "addMember" -> controller.addMember(pathVault, victimGroup.id(),
                    new AuthorizationController.MemberRequest("mallory"), auth);
                case "removeMember" -> controller.removeMember(pathVault, victimGroup.id(), "tom", auth);
                case "assignRole" -> controller.assignRole(pathVault, victimGroup.id(), victimRole.id(), auth);
                case "unassignRole" -> controller.unassignRole(pathVault, victimGroup.id(), victimRole.id(), auth);
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(ForbiddenException.class);

        assertThat(authorization.effectivePermissions(victimVault, "mallory")).isEmpty();
        assertThat(authorization.effectivePermissions(victimVault, "tom"))
            .containsExactlyInAnyOrderElementsOf(java.util.EnumSet.allOf(Permission.class));
    }

    @Test
    void should_keepThePathRuleApi_workingOnGrants() {
        var vaultId = ownerBootstrappedVault("tom");
        var auth = new TestingAuthenticationToken("tom", null);
        var reader = authorization.createGroup(vaultId, "guests");
        authorization.addMember(reader.id(), "ben");
        var note = notes.create(vaultId, "Team/plan.md", de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel.of(1), "tom");

        controller.createPathRule(vaultId.value().toString(),
            new AuthorizationController.CreatePathRuleRequest("private/", null, "DENY"), auth);
        controller.createPathRule(vaultId.value().toString(),
            new AuthorizationController.CreatePathRuleRequest("Team/plan.md", "ben", "ALLOW"), auth);

        var rules = controller.listPathRules(vaultId.value().toString(), auth);
        assertThat(rules).extracting(AuthorizationController.PathRuleResponse::pathPrefix).containsExactlyInAnyOrder("private", "Team/plan.md");
        assertThat(rules).extracting(AuthorizationController.PathRuleResponse::effect).containsExactlyInAnyOrder("DENY", "ALLOW");
        assertThat(grants.list(vaultId)).extracting(AccessGrant::target)
            .containsExactlyInAnyOrder(GrantTarget.folder("private"), GrantTarget.entry(note.id(), "Team/plan.md"));
        assertThat(guard.accessAt(vaultId, "tom", "private/x.md").allows(Permission.READ)).isFalse();
    }

    @org.junit.jupiter.api.Nested
    class Management {

        private final TestingAuthenticationToken tom = new TestingAuthenticationToken("tom", null);
        private VaultId vaultId;
        private Group owners;
        private Role ownerRole;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            vaultId = ownerBootstrappedVault("tom");
            owners = authorization.listGroups(vaultId).getFirst();
            ownerRole = authorization.listRoles(vaultId).getFirst();
        }

        private String vault() {
            return vaultId.value().toString();
        }

        @Test
        void should_renameAndRedefineRoles_andRenameGroups() {
            var role = authorization.createRole(vaultId, "reader", java.util.Set.of(Permission.READ));
            var group = authorization.createGroup(vaultId, "team");

            controller.updateRole(vault(), role.id(), new AuthorizationController.UpdateRoleRequest("editor", List.of(Permission.READ, Permission.WRITE)), tom);
            controller.updateGroup(vault(), group.id(), new AuthorizationController.UpdateGroupRequest("writers"), tom);

            assertThat(authorization.listRoles(vaultId)).filteredOn(r -> r.id().equals(role.id())).singleElement()
                .satisfies(r -> {
                    assertThat(r.name()).isEqualTo("editor");
                    assertThat(r.permissions()).containsExactlyInAnyOrder(Permission.READ, Permission.WRITE);
                });
            assertThat(authorization.listGroups(vaultId)).extracting(Group::name).contains("writers");
            assertThat(audit).contains("ROLE_CHANGED", "GROUP_CHANGED");
        }

        @Test
        void should_deleteRolesAndGroups_andTheGroupsGrants() {
            var role = authorization.createRole(vaultId, "reader", java.util.Set.of(Permission.READ));
            var group = authorization.createGroup(vaultId, "team");
            grants.put(vaultId, GrantTarget.folder("A"), GrantScope.group(group.id()), java.util.Set.of(), "tom");

            controller.deleteRole(vault(), role.id(), tom);
            controller.deleteGroup(vault(), group.id(), tom);

            assertThat(authorization.listRoles(vaultId)).containsExactly(ownerRole);
            assertThat(authorization.listGroups(vaultId)).extracting(Group::id).containsExactly(owners.id());
            assertThat(grants.list(vaultId)).isEmpty();
        }

        @Test
        void should_refuseEveryChange_thatLeavesNobodyToManageTheVault() {
            var tomOnly = "tom";

            assertThatThrownBy(() -> controller.removeMember(vault(), owners.id(), tomOnly, tom)).isInstanceOf(LastManagerException.class);
            assertThatThrownBy(() -> controller.unassignRole(vault(), owners.id(), ownerRole.id(), tom)).isInstanceOf(LastManagerException.class);
            assertThatThrownBy(() -> controller.updateRole(vault(), ownerRole.id(),
                new AuthorizationController.UpdateRoleRequest(null, List.of(Permission.READ)), tom)).isInstanceOf(LastManagerException.class);
            assertThatThrownBy(() -> controller.deleteRole(vault(), ownerRole.id(), tom)).isInstanceOf(LastManagerException.class);
            assertThatThrownBy(() -> controller.deleteGroup(vault(), owners.id(), tom)).isInstanceOf(LastManagerException.class);
            assertThatThrownBy(() -> controller.removeFromVault(vault(), tomOnly, tom)).isInstanceOf(LastManagerException.class);
            assertThat(authorization.membership(vaultId, "tom").vaultPermissions()).contains(Permission.MANAGE);
        }

        @Test
        void should_allowTheSameChanges_whenSomeoneElseCanStillManage() {
            authorization.addMember(owners.id(), "anna");

            controller.removeMember(vault(), owners.id(), "tom", tom);

            assertThat(authorization.membership(vaultId, "anna").vaultPermissions()).contains(Permission.MANAGE);
        }

        @Test
        void should_listMembers_withGroupsAndVaultRights() {
            var guests = authorization.createGroup(vaultId, "guests");
            authorization.addMember(guests.id(), "ben");

            var members = controller.listMembers(vault(), new TestingAuthenticationToken("ben", null));

            assertThat(members).extracting(AuthorizationController.MemberResponse::subject).containsExactly("ben", "tom");
            assertThat(members.getFirst().groups()).extracting(AuthorizationController.GroupRef::name).containsExactly("guests");
            assertThat(members.getFirst().permissions()).isEmpty();
            assertThatThrownBy(() -> controller.listMembers(vault(), new TestingAuthenticationToken("mallory", null)))
                .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void should_removeSomeoneFromTheVault_withAllTheirGroupsAndPersonalGrants() {
            var guests = authorization.createGroup(vaultId, "guests");
            authorization.addMember(guests.id(), "ben");
            authorization.addMember(owners.id(), "ben");
            grants.put(vaultId, GrantTarget.folder("A"), GrantScope.user("ben"), java.util.Set.of(), "tom");

            controller.removeFromVault(vault(), "ben", tom);

            assertThat(authorization.membership(vaultId, "ben").isMember()).isFalse();
            assertThat(grants.list(vaultId)).isEmpty();
            assertThat(audit).contains("MEMBER_REMOVED");
        }

        @Test
        void should_letAMemberLeave_withoutManageRights_butNotRemoveOthers() {
            var guests = authorization.createGroup(vaultId, "guests");
            authorization.addMember(guests.id(), "ben");
            authorization.addMember(guests.id(), "cleo");
            var ben = new TestingAuthenticationToken("ben", null);

            assertThatThrownBy(() -> controller.removeFromVault(vault(), "cleo", ben)).isInstanceOf(ForbiddenException.class);
            controller.removeFromVault(vault(), "ben", ben);

            assertThat(authorization.membership(vaultId, "ben").isMember()).isFalse();
            assertThat(authorization.membership(vaultId, "cleo").isMember()).isTrue();
        }

        @Test
        void should_refuseRolesAndGroupsOfOtherVaults() {
            var foreignVault = ownerBootstrappedVault("eve");
            var foreignRole = authorization.listRoles(foreignVault).getFirst();
            var foreignGroup = authorization.listGroups(foreignVault).getFirst();

            assertThatThrownBy(() -> controller.deleteRole(vault(), foreignRole.id(), tom)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> controller.deleteGroup(vault(), foreignGroup.id(), tom)).isInstanceOf(ForbiddenException.class);
        }
    }
}
