package de.tstieh.stoneintelligence.platform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdpMembershipMaintenanceTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakePeopleDirectory people = new FakePeopleDirectory();
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final List<VaultId> announced = new ArrayList<>();
    private final Instant now = Instant.now();

    @Test
    void should_expireTokenGroupsAfterAWeek_andTellTheVaults_andForgetOldNames() {
        announcements.addAccessListener(announced::add);
        var vault = VaultId.newId();
        authorization.linkIdpGroup(authorization.createGroup(vault, "Vertrieb").id(), "sales");
        authorization.rememberTokenGroups("gone", Set.of("sales"), now.minus(Duration.ofDays(8)));
        authorization.rememberTokenGroups("here", Set.of("sales"), now);
        people.remember("gone-long-ago", "Alt", now.minus(Duration.ofDays(500)));
        people.remember("here", "Da", now);

        new IdpMembershipMaintenance(authorization, people, announcements, () -> now).sweep();

        assertThat(announced).containsExactly(vault);
        assertThat(authorization.countIdpMembers(vault)).containsValue(1);
        assertThat(people.displayNames(List.of("gone-long-ago", "here"))).containsOnlyKeys("here");
    }
}
