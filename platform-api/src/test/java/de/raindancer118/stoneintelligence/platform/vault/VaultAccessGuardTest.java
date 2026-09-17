package de.raindancer118.stoneintelligence.platform.vault;

import java.util.Set;
import de.raindancer118.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.identity.RuleEffect;
import de.raindancer118.stoneintelligence.platform.identity.RuleScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultAccessGuardTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final VaultAccessGuard guard = new VaultAccessGuard(authorization);

    @Test
    void should_throwForbidden_when_subjectHasNoPermissionInVault() {
        var vaultId = de.raindancer118.stoneintelligence.domain.id.VaultId.newId();

        assertThatThrownBy(() -> guard.require(vaultId, "mallory", Permission.READ))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_pass_when_subjectHasRequiredPermissionViaGroupRole() {
        var vaultId = de.raindancer118.stoneintelligence.domain.id.VaultId.newId();
        var role = authorization.createRole(vaultId, "reader", Set.of(Permission.READ));
        var group = authorization.createGroup(vaultId, "readers");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "tom");

        assertThatCode(() -> guard.require(vaultId, "tom", Permission.READ)).doesNotThrowAnyException();
    }

    @Test
    void should_throwForbidden_when_pathIsDeniedDespiteBasePermission() {
        var vaultId = de.raindancer118.stoneintelligence.domain.id.VaultId.newId();
        var role = authorization.createRole(vaultId, "editor", Set.of(Permission.WRITE));
        var group = authorization.createGroup(vaultId, "editors");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "tom");
        authorization.createPathRule(vaultId, "private", RuleScope.everyone(), RuleEffect.DENY);

        assertThatThrownBy(() -> guard.require(vaultId, "tom", Permission.WRITE, "private/secret.md"))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_pass_when_pathHasNoMatchingRule() {
        var vaultId = de.raindancer118.stoneintelligence.domain.id.VaultId.newId();
        var role = authorization.createRole(vaultId, "editor", Set.of(Permission.WRITE));
        var group = authorization.createGroup(vaultId, "editors");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "tom");

        assertThatCode(() -> guard.require(vaultId, "tom", Permission.WRITE, "notes/anything.md"))
            .doesNotThrowAnyException();
    }
}
