package de.raindancer118.stoneintelligence.platform.vault;

import de.raindancer118.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class VaultControllerTest {

    private final FakeVaultRepository vaults = new FakeVaultRepository();
    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final VaultController controller = new VaultController(vaults, authorization);

    @Test
    void should_grantCreatorFullPermissions_when_vaultIsCreated() {
        var authentication = new TestingAuthenticationToken("tom", null);

        var response = controller.create(new VaultController.CreateVaultRequest("my-vault"), authentication);

        assertThat(response.name()).isEqualTo("my-vault");
        var vaultId = de.raindancer118.stoneintelligence.domain.id.VaultId.of(response.id());
        assertThat(authorization.effectivePermissions(vaultId, "tom"))
            .containsExactlyInAnyOrder(Permission.READ, Permission.WRITE, Permission.DELETE, Permission.CREATE, Permission.MANAGE);
    }

    @Test
    void should_notGrantPermissions_when_subjectDidNotCreateTheVault() {
        var authentication = new TestingAuthenticationToken("tom", null);
        var response = controller.create(new VaultController.CreateVaultRequest("my-vault"), authentication);
        var vaultId = de.raindancer118.stoneintelligence.domain.id.VaultId.of(response.id());

        assertThat(authorization.effectivePermissions(vaultId, "mallory")).isEmpty();
    }

    @Test
    void should_listOnlyVaultsSubjectHasAccessTo() {
        var tom = new TestingAuthenticationToken("tom", null);
        var mallory = new TestingAuthenticationToken("mallory", null);
        var tomsVault = controller.create(new VaultController.CreateVaultRequest("toms-vault"), tom);
        controller.create(new VaultController.CreateVaultRequest("mallorys-vault"), mallory);

        var tomsVaults = controller.list(tom);

        assertThat(tomsVaults).extracting(VaultController.VaultResponse::id).containsExactly(tomsVault.id());
    }
}
