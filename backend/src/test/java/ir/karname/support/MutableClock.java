package ir.karname.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can move; business code only sees {@link Clock}. */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public MutableClock(Instant initial, ZoneId zone) {
        this(new AtomicReference<>(initial), zone);
    }

    private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
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
        return instant.get();
    }

    public void setInstant(Instant value) {
        instant.set(value);
    }

    /** Moves the clock to noon of the given local date in this clock's zone. */
    public void setDate(LocalDate date) {
        instant.set(date.atTime(LocalTime.NOON).atZone(zone).toInstant());
    }

    public void advance(Duration duration) {
        instant.updateAndGet(i -> i.plus(duration));
    }
}
