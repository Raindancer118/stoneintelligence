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
    private final VaultAccessGuard guard = new VaultAccessGuard(authorization);
    private final AuthorizationController controller = new AuthorizationController(authorization, guard);

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
    void should_createAndListPathRule_when_actorHasDeletePermission() {
        var vaultId = ownerBootstrappedVault("tom");
        var auth = new TestingAuthenticationToken("tom", null);

        controller.createPathRule(vaultId.value().toString(),
            new AuthorizationController.CreatePathRuleRequest("private", null, "DENY"), auth);

        var rules = controller.listPathRules(vaultId.value().toString(), auth);
        assertThat(rules).extracting(AuthorizationController.PathRuleResponse::pathPrefix).containsExactly("private");
        assertThat(rules).extracting(AuthorizationController.PathRuleResponse::effect).containsExactly("DENY");
    }
}
