package de.tstieh.stoneintelligence.platform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Anzeigenamen zu Subjects (ADR 0013): mit {@code sub}/{@code oid} als Principal-Claim sind Subjects
 * nichtssagende IDs. Der Name kommt aus dem Token der Person oder - vorrangig - aus SCIM.
 */
public interface PeopleDirectory {

    /** Wie lange ein Name nach der letzten Anmeldung bleibt (Datenminimierung, s. Datenschutzerklaerung). */
    Duration RETENTION = Duration.ofDays(395);

    void remember(String subject, String displayName, Instant seenAt);

    /** Nur Subjects mit bekanntem Namen sind enthalten. */
    Map<String, String> displayNames(Collection<String> subjects);

    int forgetNotSeenSince(Instant before);
}
