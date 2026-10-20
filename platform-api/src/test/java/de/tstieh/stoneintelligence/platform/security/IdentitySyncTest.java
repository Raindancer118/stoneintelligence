package de.tstieh.stoneintelligence.platform.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.FakePeopleDirectory;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentitySyncTest {

    private final List<String> writes = new ArrayList<>();
    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository() {
        @Override
        public synchronized Set<VaultId> rememberTokenGroups(String subject, Set<String> idpGroups, Instant seenAt) {
            writes.add(subject + " " + new java.util.TreeSet<>(idpGroups));
            return super.rememberTokenGroups(subject, idpGroups, seenAt);
        }
    };
    private final FakePeopleDirectory people = new FakePeopleDirectory();
    private final List<VaultId> announced = new ArrayList<>();
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    private final IdentitySync sync = new IdentitySync(authorization, people, announcements, now::get);
    private final VaultId vault = VaultId.newId();

    {
        announcements.addAccessListener(announced::add);
        authorization.linkIdpGroup(authorization.createGroup(vault, "Alle").id(), "all-employees");
        authorization.linkIdpGroup(authorization.createGroup(vault, "Vertrieb").id(), "sales");
    }

    @Test
    void should_rememberOnlyLinkedGroups_andNotWriteOnEveryRequest() {
        sync.seen("ben", "Ben", List.of("all-employees", "chess-club"));
        sync.seen("ben", "Ben", List.of("all-employees", "chess-club"));
        now.set(now.get().plus(Duration.ofMinutes(30)));
        sync.seen("ben", "Ben", List.of("chess-club", "all-employees"));

        assertThat(writes).containsExactly("ben [all-employees]");
    }

    @Test
    void should_writeAgain_whenGroupsChange_orTheLastSightingGetsOld() {
        sync.seen("ben", "Ben", List.of("all-employees"));
        sync.seen("ben", "Ben", List.of("all-employees", "sales"));
        now.set(now.get().plus(Duration.ofHours(2)));
        sync.seen("ben", "Ben", List.of("all-employees", "sales"));

        assertThat(writes).containsExactly("ben [all-employees]", "ben [all-employees, sales]", "ben [all-employees, sales]");
    }

    // Wer in keiner verknuepften Gruppe (mehr) ist, verliert die Mitgliedschaft auch ohne Gruppen-Claim.
    @Test
    void should_forgetGroups_whenTheTokenHasNone() {
        sync.seen("ben", "Ben", List.of("sales"));
        sync.seen("ben", "Ben", null);

        assertThat(writes).containsExactly("ben [sales]", "ben []");
    }

    // Wird eine IdP-Gruppe neu verknuepft, sollen ihre Leute nicht bis zu einer Stunde warten.
    @Test
    void should_pickUpNewlyLinkedGroups_withinAMinute() {
        sync.seen("ben", "Ben", List.of("support"));
        authorization.linkIdpGroup(authorization.createGroup(VaultId.newId(), "Support").id(), "support");
        now.set(now.get().plus(Duration.ofMinutes(2)));
        sync.seen("ben", "Ben", List.of("support"));

        assertThat(writes).containsExactly("ben []", "ben [support]");
    }

    // Ueber diese Instanz verknuepft: gleich bei der naechsten Anfrage, ohne Minute Wartezeit.
    @Test
    void should_pickUpALinkMadeOnThisInstance_immediately() {
        sync.seen("ben", "Ben", List.of("support"));
        authorization.linkIdpGroup(authorization.createGroup(vault, "Support").id(), "support");
        announcements.announceAccessChanged(vault);
        sync.seen("ben", "Ben", List.of("support"));

        assertThat(writes).containsExactly("ben []", "ben [support]");
    }

    // Offene Verbindungen sollen sofort nach den neuen Rechten laufen - aber nur, wenn sich wirklich etwas aendert.
    @Test
    void should_announceAccessChanges_onlyForVaultsWhoseMembershipChanged() {
        sync.seen("ben", "Ben", List.of("sales"));
        now.set(now.get().plus(Duration.ofHours(2)));
        sync.seen("ben", "Ben", List.of("sales"));
        sync.seen("ben", "Ben", List.of());

        assertThat(announced).containsExactly(vault, vault);
    }

    @Test
    void should_rememberTheDisplayName_andPickUpRenames() {
        sync.seen("5f2c-oid", "Ben Becker", List.of());
        sync.seen("5f2c-oid", "Ben Schmidt", List.of());
        sync.seen("anonymous", null, List.of());

        assertThat(people.displayNames(List.of("5f2c-oid", "anonymous"))).containsExactly(java.util.Map.entry("5f2c-oid", "Ben Schmidt"));
    }

    // Grosse Firmen: der Speicher fuer "zuletzt gesehen" waechst nicht mit jeder Person, die je kam.
    @Test
    void should_dropStaleSightings_whenTrackingTooManyPeople() {
        for (int i = 0; i < IdentitySync.MAX_TRACKED; i++) {
            sync.seen("person-" + i, null, List.of());
        }
        now.set(now.get().plus(Duration.ofHours(2)));
        sync.seen("late", null, List.of());

        assertThat(sync.tracked()).isEqualTo(1);
    }
}
