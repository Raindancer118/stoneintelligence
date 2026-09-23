package de.raindancer118.stoneintelligence.platform.invitation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.regex.Pattern;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.vault.FakeVaultRepository;
import de.raindancer118.stoneintelligence.platform.vault.ForbiddenException;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationServiceTest {

    private static final Pattern INVITE_LINK = Pattern.compile("https://kb\\.example/invite/([A-Za-z0-9_-]{20,})");

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeVaultRepository vaults = new FakeVaultRepository();
    private final FakeInvitationRepository invitations = new FakeInvitationRepository();
    private final FakeUserDirectory directory = new FakeUserDirectory()
        .with("anna", "Anna Arendt", "anna@example.org")
        .with("ben", "Ben Becker", "ben@example.org");
    private final RecordingMailer mailer = new RecordingMailer();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-23T10:00:00Z"));
    private final InvitationService service = new InvitationService(invitations, directory, mailer, authorization,
        new VaultAccessGuard(authorization), vaults, clock, new InvitationSettings("https://kb.example", Duration.ofDays(14)));

    private VaultId vaultId;

    @BeforeEach
    void ownerVault() {
        vaultId = vaults.create("Team-Notizen").id();
        var owner = authorization.createRole(vaultId, "owner", EnumSet.allOf(Permission.class));
        var owners = authorization.createGroup(vaultId, "owners");
        authorization.assignRole(owners.id(), owner.id());
        authorization.addMember(owners.id(), "tom");
    }

    private String inviteLinkToken(OutgoingMail mail) {
        var matcher = INVITE_LINK.matcher(mail.text());
        assertThat(matcher.find()).as("Einladungslink in der Mail: %s", mail.text()).isTrue();
        return matcher.group(1);
    }

    @Nested
    class Suche {

        @Test
        void should_findPeopleInTheDirectory_withMaskedEmail_andMarkExistingMembers() {
            service.addExisting(vaultId, "tom", "ben", InviteAccess.EDIT);

            var results = service.searchPeople(vaultId, "tom", "example");

            assertThat(results).extracting(PersonSuggestion::username, PersonSuggestion::name, PersonSuggestion::maskedEmail,
                    PersonSuggestion::alreadyMember)
                .containsExactly(
                    org.assertj.core.groups.Tuple.tuple("anna", "Anna Arendt", "a***@example.org", false),
                    org.assertj.core.groups.Tuple.tuple("ben", "Ben Becker", "b***@example.org", true));
        }

        @Test
        void should_requireAtLeastTwoCharacters_beforeSearching() {
            assertThat(service.searchPeople(vaultId, "tom", " a ")).isEmpty();
        }

        // Nur wer den Vault verwaltet, darf das Verzeichnis durchsuchen - sonst koennte jedes
        // Mitglied die Namensliste aller Konten abgreifen.
        @Test
        void should_refuseDirectorySearch_forMembersWithoutManageRight() {
            service.addExisting(vaultId, "tom", "ben", InviteAccess.EDIT);

            assertThatThrownBy(() -> service.searchPeople(vaultId, "ben", "an")).isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class BestehendeKonten {

        @Test
        void should_addAnExistingAccount_withEditRights_butWithoutManageRights() {
            var result = service.addExisting(vaultId, "tom", "anna", InviteAccess.EDIT);

            assertThat(result.status()).isEqualTo(InviteStatus.ADDED);
            assertThat(authorization.effectivePermissions(vaultId, "anna"))
                .containsExactlyInAnyOrder(Permission.READ, Permission.WRITE, Permission.CREATE, Permission.DELETE);
        }

        @Test
        void should_addReadOnly_when_requested() {
            service.addExisting(vaultId, "tom", "anna", InviteAccess.READ);

            assertThat(authorization.effectivePermissions(vaultId, "anna")).containsExactly(Permission.READ);
        }

        @Test
        void should_tellThePersonByMail_thatTheyWereAdded() {
            service.addExisting(vaultId, "tom", "anna", InviteAccess.EDIT);

            assertThat(mailer.sent).singleElement().satisfies(mail -> {
                assertThat(mail.to()).isEqualTo("anna@example.org");
                assertThat(mail.subject()).contains("Team-Notizen");
                assertThat(mail.text()).contains("https://kb.example");
            });
        }

        @Test
        void should_rejectUnknownUsernames_insteadOfCreatingOrphanMemberships() {
            assertThatThrownBy(() -> service.addExisting(vaultId, "tom", "gibtsnicht", InviteAccess.EDIT))
                .isInstanceOf(InvitationException.class);
        }

        @Test
        void should_reportAlreadyMember_insteadOfDuplicating() {
            service.addExisting(vaultId, "tom", "anna", InviteAccess.EDIT);

            assertThat(service.addExisting(vaultId, "tom", "anna", InviteAccess.EDIT).status()).isEqualTo(InviteStatus.ALREADY_MEMBER);
        }
    }

    @Nested
    class PerEmail {

        @Test
        void should_addDirectly_when_theEmailBelongsToAnExistingAccount() {
            var result = service.inviteByEmail(vaultId, "tom", "Anna@Example.org ", InviteAccess.EDIT);

            assertThat(result.status()).isEqualTo(InviteStatus.ADDED);
            assertThat(directory.createdInvitationsFor).isEmpty();
            assertThat(authorization.effectivePermissions(vaultId, "anna")).contains(Permission.WRITE);
        }

        @Test
        void should_createAnEnrollmentInvitation_andMailALink_forNewPeople() {
            var result = service.inviteByEmail(vaultId, "tom", "neu@example.org", InviteAccess.EDIT);

            assertThat(result.status()).isEqualTo(InviteStatus.INVITED);
            assertThat(directory.createdInvitationsFor).containsExactly("neu@example.org");
            var mail = mailer.sent.getLast();
            assertThat(mail.to()).isEqualTo("neu@example.org");
            inviteLinkToken(mail);
            assertThat(service.listPending(vaultId, "tom")).extracting(PendingInvitation::email).containsExactly("neu@example.org");
        }

        @Test
        void should_rejectMalformedAddresses() {
            assertThatThrownBy(() -> service.inviteByEmail(vaultId, "tom", "kein-at-zeichen", InviteAccess.EDIT))
                .isInstanceOf(InvitationException.class);
        }

        @Test
        void should_stillInvite_when_theDirectoryIsNotConfigured_withoutAnEnrollmentLink() {
            directory.available = false;

            service.inviteByEmail(vaultId, "tom", "neu@example.org", InviteAccess.EDIT);

            var token = inviteLinkToken(mailer.sent.getLast());
            assertThat(service.describe(token).enrollmentUrl()).isNull();
        }

        // Kommt die Mail nicht an, darf keine Einladung zurueckbleiben, von der niemand weiss.
        @Test
        void should_undoTheInvitation_when_theMailCannotBeDelivered() {
            mailer.failing = true;

            assertThatThrownBy(() -> service.inviteByEmail(vaultId, "tom", "neu@example.org", InviteAccess.EDIT))
                .isInstanceOf(InvitationException.class).hasMessageContaining("E-Mail");

            assertThat(service.listPending(vaultId, "tom")).isEmpty();
            assertThat(directory.deletedInvitations).containsExactly("ak-1");
        }

        @Test
        void should_keepTheMembership_when_onlyTheNotificationMailFails() {
            mailer.failing = true;

            assertThat(service.addExisting(vaultId, "tom", "anna", InviteAccess.EDIT).status()).isEqualTo(InviteStatus.ADDED);
            assertThat(authorization.effectivePermissions(vaultId, "anna")).contains(Permission.WRITE);
        }

        @Test
        void should_refuseInvitations_fromMembersWithoutManageRight() {
            service.addExisting(vaultId, "tom", "ben", InviteAccess.EDIT);

            assertThatThrownBy(() -> service.inviteByEmail(vaultId, "ben", "x@example.org", InviteAccess.EDIT))
                .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class Annehmen {

        private String invite() {
            service.inviteByEmail(vaultId, "tom", "neu@example.org", InviteAccess.EDIT);
            return inviteLinkToken(mailer.sent.getLast());
        }

        @Test
        void should_describeAPendingInvitation_withoutLogin() {
            var info = service.describe(invite());

            assertThat(info.state()).isEqualTo(InvitationState.PENDING);
            assertThat(info.vaultName()).isEqualTo("Team-Notizen");
            assertThat(info.invitedBy()).isEqualTo("tom");
            assertThat(info.maskedEmail()).isEqualTo("n***@example.org");
            assertThat(info.enrollmentUrl()).startsWith("https://portal.example/if/flow/invite/");
        }

        @Test
        void should_addTheAcceptingAccount_andUseTheInvitationUpOnce() {
            var token = invite();

            var joined = service.accept(token, "neu");

            assertThat(joined.vaultName()).isEqualTo("Team-Notizen");
            assertThat(authorization.effectivePermissions(vaultId, "neu")).contains(Permission.WRITE);
            assertThat(directory.deletedInvitations).containsExactly("ak-1");
            assertThat(service.describe(token).state()).isEqualTo(InvitationState.ACCEPTED);
            assertThatThrownBy(() -> service.accept(token, "mallory")).isInstanceOf(InvitationException.class);
            assertThat(authorization.effectivePermissions(vaultId, "mallory")).isEmpty();
        }

        @Test
        void should_refuseExpiredInvitations() {
            var token = invite();
            clock.advance(Duration.ofDays(15));

            assertThat(service.describe(token).state()).isEqualTo(InvitationState.EXPIRED);
            assertThatThrownBy(() -> service.accept(token, "neu")).isInstanceOf(InvitationException.class);
        }

        @Test
        void should_refuseRevokedInvitations_andRemoveTheEnrollmentLink() {
            var token = invite();
            var pending = service.listPending(vaultId, "tom").getFirst();

            service.revoke(vaultId, "tom", pending.id());

            assertThat(directory.deletedInvitations).containsExactly("ak-1");
            assertThatThrownBy(() -> service.accept(token, "neu")).isInstanceOf(InvitationException.class);
            assertThat(service.listPending(vaultId, "tom")).isEmpty();
        }

        @Test
        void should_rejectUnknownTokens() {
            assertThatThrownBy(() -> service.describe("x".repeat(43))).isInstanceOf(InvitationException.class);
        }

        // Wer mit genau der eingeladenen Adresse das erste Mal ankommt, soll nichts mehr tun muessen.
        @Test
        void should_acceptPendingInvitationsAutomatically_forTheInvitedEmail() {
            invite();

            var joined = service.acceptPendingForEmail("neu", "NEU@example.org");

            assertThat(joined).containsExactly(vaultId);
            assertThat(authorization.effectivePermissions(vaultId, "neu")).contains(Permission.WRITE);
            assertThat(service.listPending(vaultId, "tom")).isEmpty();
        }
    }

    @Test
    void should_forgetClosedInvitations_afterThirtyDays() {
        service.inviteByEmail(vaultId, "tom", "neu@example.org", InviteAccess.EDIT);
        clock.advance(Duration.ofDays(14 + 31));

        assertThat(service.purgeClosed()).isEqualTo(1);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
