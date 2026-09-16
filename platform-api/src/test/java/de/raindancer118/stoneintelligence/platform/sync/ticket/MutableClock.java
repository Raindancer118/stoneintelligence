package de.raindancer118.stoneintelligence.platform.sync.ticket;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** Test-Double: ein {@link Clock}, dessen Zeit sich innerhalb eines Tests vorstellen laesst. */
final class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    void advanceTo(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
