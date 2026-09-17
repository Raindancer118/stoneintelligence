package de.raindancer118.stoneintelligence.platform.identity;

import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.vault.ForbiddenException;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.Test;
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
