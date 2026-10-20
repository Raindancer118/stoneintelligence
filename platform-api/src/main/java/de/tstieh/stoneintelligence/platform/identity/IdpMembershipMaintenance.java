package de.tstieh.stoneintelligence.platform.identity;

import java.time.Instant;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Raeumt ab, was mit der Zeit verfaellt (ADR 0013). Eine Token-Gruppe, die eine Woche niemand
 * bestaetigt hat, gilt zwar schon laut Sicht nicht mehr - ohne Loeschen aenderte sich aber keine
 * Zugriffs-Version, und die Caches hielten die Person weiter fuer ein Mitglied.
 */
@Component
public class IdpMembershipMaintenance {

    private static final Logger log = LoggerFactory.getLogger(IdpMembershipMaintenance.class);

    private final AuthorizationRepository authorization;
    private final PeopleDirectory people;
    private final VaultAnnouncementService announcements;
    private final Supplier<Instant> clock;

    @Autowired
    public IdpMembershipMaintenance(AuthorizationRepository authorization, PeopleDirectory people,
                                    VaultAnnouncementService announcements) {
        this(authorization, people, announcements, Instant::now);
    }

    IdpMembershipMaintenance(AuthorizationRepository authorization, PeopleDirectory people,
                             VaultAnnouncementService announcements, Supplier<Instant> clock) {
        this.authorization = authorization;
        this.people = people;
        this.announcements = announcements;
        this.clock = clock;
    }

    /** Alle 10 Minuten: so lange bleibt jemand hoechstens ueber die Woche hinaus Mitglied. */
    @Scheduled(initialDelay = 90_000, fixedDelay = 600_000)
    public void sweep() {
        var now = clock.get();
        var vaults = authorization.forgetTokenGroupsSeenBefore(now.minus(AuthorizationRepository.TOKEN_GROUPS_VALID_FOR));
        vaults.forEach(announcements::announceAccessChanged);
        var forgotten = people.forgetNotSeenSince(now.minus(PeopleDirectory.RETENTION));
        if (!vaults.isEmpty() || forgotten > 0) {
            log.info("Expired IdP memberships in {} vault(s), forgot {} display name(s)", vaults.size(), forgotten);
        }
    }
}
