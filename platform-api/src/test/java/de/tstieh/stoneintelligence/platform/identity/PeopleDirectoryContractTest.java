package de.tstieh.stoneintelligence.platform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class PeopleDirectoryContractTest {

    protected abstract PeopleDirectory people();

    private final Instant now = Instant.now();

    @Test
    void should_returnKnownNamesOnly_andKeepTheLatestName() {
        var people = people();
        people.remember("5f2c-oid", "Ben Becker", now);
        people.remember("5f2c-oid", "Ben Schmidt", now.plusSeconds(1));

        assertThat(people.displayNames(List.of("5f2c-oid", "unknown"))).isEqualTo(Map.of("5f2c-oid", "Ben Schmidt"));
        assertThat(people.displayNames(List.of())).isEmpty();
    }

    @Test
    void should_forgetPeople_whoHaveNotSignedInWithinTheRetention() {
        var people = people();
        people.remember("old", "Alt", now.minus(Duration.ofDays(400)));
        people.remember("recent", "Neu", now);

        assertThat(people.forgetNotSeenSince(now.minus(PeopleDirectory.RETENTION))).isEqualTo(1);
        assertThat(people.displayNames(List.of("old", "recent"))).containsOnlyKeys("recent");
    }

    // Ein verspaeteter Schreibvorgang darf die letzte Anmeldung nicht zuruecksetzen.
    @Test
    void should_neverMoveLastSeenBackwards() {
        var people = people();
        people.remember("ben", "Ben", now);
        people.remember("ben", "Ben", now.minus(Duration.ofDays(500)));

        assertThat(people.forgetNotSeenSince(now.minus(PeopleDirectory.RETENTION))).isZero();
    }
}
