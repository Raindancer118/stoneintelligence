package de.raindancer118.stoneintelligence.platform.vault;

import de.raindancer118.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class VaultControllerTest {

    private final FakeVaultRepository vaults = new FakeVaultRepository();
    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final de.raindancer118.stoneintelligence.platform.invitation.RecordingMailer mailer =
        new de.raindancer118.stoneintelligence.platform.invitation.RecordingMailer();
    private final de.raindancer118.stoneintelligence.platform.invitation.InvitationService invitations =
        new de.raindancer118.stoneintelligence.platform.invitation.InvitationService(
            new de.raindancer118.stoneintelligence.platform.invitation.FakeInvitationRepository(),
            new de.raindancer118.stoneintelligence.platform.invitation.FakeUserDirectory(), mailer, authorization,
            new VaultAccessGuard(authorization, new de.raindancer118.stoneintelligence.platform.identity.FakeAccessGrantRepository(new de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository())), vaults, java.time.Clock.systemUTC(),
            new de.raindancer118.stoneintelligence.platform.invitation.InvitationSettings("https://kb.example", java.time.Duration.ofDays(14)));
    private final VaultController controller = new VaultController(vaults, authorization, invitations,
        new VaultAccessGuard(authorization, new de.raindancer118.stoneintelligence.platform.identity.FakeAccessGrantRepository(new de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository())));

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

    /**
     * Wer ueber eine E-Mail-Einladung ein Konto angelegt hat und sich dann einfach anmeldet, soll
     * den Vault sofort sehen - ohne den Link aus der Mail noch einmal oeffnen zu muessen.
     */
    @Test
    void should_acceptPendingInvitationsForTheSignedInEmail_whenListingVaults() {
        var tom = new TestingAuthenticationToken("tom", null);
        var vault = controller.create(new VaultController.CreateVaultRequest("Team"), tom);
        invitations.inviteByEmail(de.raindancer118.stoneintelligence.domain.id.VaultId.of(vault.id()), "tom", "neu@example.org",
            de.raindancer118.stoneintelligence.platform.invitation.InviteAccess.EDIT);
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none")
            .claim("preferred_username", "neu").claim("email", "Neu@example.org").claim("email_verified", true).build();
        var newcomer = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, java.util.List.of(), "neu");

        assertThat(controller.list(newcomer)).extracting(VaultController.VaultResponse::name).containsExactly("Team");
    }

    /**
     * Ohne bestaetigte Adresse koennte sich jede Person, die ein Konto mit fremder E-Mail anlegt,
     * deren Einladungen aneignen. Authentik stellt standardmaessig email_verified=false aus - dann
     * bleibt nur der (sichere) Weg ueber den Link aus der Mail.
     */
    @Test
    void should_notAcceptInvitations_forUnverifiedEmailClaims() {
        var tom = new TestingAuthenticationToken("tom", null);
        var vault = controller.create(new VaultController.CreateVaultRequest("Team"), tom);
        invitations.inviteByEmail(de.raindancer118.stoneintelligence.domain.id.VaultId.of(vault.id()), "tom", "neu@example.org",
            de.raindancer118.stoneintelligence.platform.invitation.InviteAccess.EDIT);
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none")
            .claim("preferred_username", "imposter").claim("email", "neu@example.org").claim("email_verified", false).build();
        var imposter = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, java.util.List.of(), "imposter");

        assertThat(controller.list(imposter)).isEmpty();
    }

    @Test
    void should_renameAVault_onlyForWhoManagesIt() {
        var tom = new org.springframework.security.authentication.TestingAuthenticationToken("tom", null);
        var created = controller.create(new VaultController.CreateVaultRequest("Alt"), tom);
        var group = authorization.createGroup(de.raindancer118.stoneintelligence.domain.id.VaultId.of(created.id()), "guests");
        authorization.addMember(group.id(), "ben");

        var renamed = controller.rename(created.id(), new VaultController.RenameVaultRequest("Neu"), tom);

        org.assertj.core.api.Assertions.assertThat(renamed.name()).isEqualTo("Neu");
        org.assertj.core.api.Assertions.assertThat(controller.list(tom)).extracting(VaultController.VaultResponse::name).containsExactly("Neu");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.rename(created.id(),
                new VaultController.RenameVaultRequest("Meins"), new org.springframework.security.authentication.TestingAuthenticationToken("ben", null)))
            .isInstanceOf(ForbiddenException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.rename(created.id(), new VaultController.RenameVaultRequest(" "), tom))
            .isInstanceOf(de.raindancer118.stoneintelligence.platform.identity.InvalidGrantException.class);
    }
}
