package de.tstieh.stoneintelligence.platform.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.PeopleDirectory;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Uebernimmt, was ein Token ueber die Person sagt (ADR 0013): verknuepfte IdP-Gruppen und den
 * Anzeigenamen. Geschrieben wird nur, wenn sich etwas geaendert hat oder die letzte Sichtung eine
 * Stunde alt ist - nicht bei jeder Anfrage. Aendert sich dadurch eine Mitgliedschaft, laufen offene
 * Verbindungen der betroffenen Vaults sofort nach den neuen Rechten.
 */
@Component
public class IdentitySync {

    static final Duration REFRESH = Duration.ofHours(1);
    static final int MAX_TRACKED = 50_000;
    private static final Duration LINKED_TTL = Duration.ofMinutes(1);

    private final AuthorizationRepository authorization;
    private final PeopleDirectory people;
    private final VaultAnnouncementService announcements;
    private final Supplier<Instant> clock;
    private final ConcurrentHashMap<String, Seen> lastSeen = new ConcurrentHashMap<>();
    private volatile Set<String> linked;
    private volatile Instant linkedAt = Instant.MIN;

    @Autowired
    public IdentitySync(AuthorizationRepository authorization, PeopleDirectory people, VaultAnnouncementService announcements) {
        this(authorization, people, announcements, Instant::now);
    }

    IdentitySync(AuthorizationRepository authorization, PeopleDirectory people, VaultAnnouncementService announcements,
                 Supplier<Instant> clock) {
        this.authorization = authorization;
        this.people = people;
        this.announcements = announcements;
        this.clock = clock;
        // Eine neue Verknuepfung ueber diese Instanz wirkt sofort, nicht erst nach LINKED_TTL.
        announcements.addAccessListener(vault -> linkedAt = Instant.MIN);
    }

    /** {@code displayName}/{@code groups}: aus dem Token, {@code null} wenn der Claim fehlt. */
    public void seen(String subject, String displayName, Collection<String> groups) {
        var now = clock.get();
        var linkedNow = linked(now);
        var relevant = new TreeSet<String>();
        if (groups != null) {
            groups.stream().filter(linkedNow::contains).forEach(relevant::add);
        }
        var previous = lastSeen.get(subject);
        var fresh = previous != null && Duration.between(previous.at(), now).compareTo(REFRESH) < 0;
        if (fresh && previous.linked() == linkedNow && previous.groups().equals(relevant)
                && Objects.equals(previous.displayName(), displayName)) {
            return;
        }
        if (!fresh || previous.linked() != linkedNow || !previous.groups().equals(relevant)) {
            authorization.rememberTokenGroups(subject, relevant, now).forEach(announcements::announceAccessChanged);
        }
        if (displayName != null && !displayName.isBlank()) {
            people.remember(subject, displayName.strip(), now);
        }
        trimIfFull(now);
        lastSeen.put(subject, new Seen(Set.copyOf(relevant), linkedNow, displayName, now));
    }

    int tracked() {
        return lastSeen.size();
    }

    /** Wer laenger als {@link #REFRESH} nicht da war, wuerde ohnehin neu geschrieben - sein Eintrag kann weg. */
    private void trimIfFull(Instant now) {
        if (lastSeen.size() >= MAX_TRACKED) {
            lastSeen.values().removeIf(seen -> Duration.between(seen.at(), now).compareTo(REFRESH) >= 0);
        }
    }

    private Set<String> linked(Instant now) {
        if (linked == null || Duration.between(linkedAt, now).compareTo(LINKED_TTL) >= 0) {
            var fresh = authorization.linkedIdpGroups();
            // Gleiche Menge: dasselbe Objekt behalten, sonst schriebe jede Minute jede Person neu.
            linked = fresh.equals(linked) ? linked : fresh;
            linkedAt = now;
        }
        return linked;
    }

    /** {@code linked}: die Menge verknuepfter Gruppen zum Zeitpunkt der Sichtung (Identitaet, nicht Inhalt). */
    private record Seen(Set<String> groups, Set<String> linked, String displayName, Instant at) {
    }
}
