package net.exylia.lib.util;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Days, weeks and months that start at a reset hour, in a configured zone:
 * when daily and weekly things (missions, rewards, limits) roll over.
 *
 * <pre>{@code
 * PeriodClock clock = PeriodClock.of("Europe/Madrid", 5, DayOfWeek.MONDAY);
 * String today = clock.key(PeriodClock.Period.DAILY);        // "2026-10-27", the same on every server
 * long left = clock.remaining(PeriodClock.Period.WEEKLY);    // millis until the week rolls over
 * }</pre>
 *
 * <p>Everything is derived from the wall clock, so nothing has to be scheduled:
 * a player who logs in after a week away is simply in a different period than
 * their rows say. A server that was offline over a reset needs no catch-up.
 *
 * <p>A day starts at the reset hour rather than at midnight: 03:00 with a reset
 * hour of 5 still belongs to yesterday. A week starts on {@code weekStart} and a
 * month on its first day, both at the reset hour.
 *
 * <p>Cheap to build on every call, which is how callers pick up a reloaded
 * reset hour or zone: the zone id is parsed once per distinct configured value.
 *
 * @param zone      the zone periods are worked out in
 * @param resetHour the hour, 0-23, a day starts at
 * @param weekStart the day a week starts on
 * @since 1.381.0
 */
public record PeriodClock(ZoneId zone, int resetHour, DayOfWeek weekStart) {

    /** How long a period is. */
    public enum Period {
        DAILY(ChronoUnit.DAYS), WEEKLY(ChronoUnit.WEEKS), MONTHLY(ChronoUnit.MONTHS);

        private final ChronoUnit unit;

        Period(ChronoUnit unit) {
            this.unit = unit;
        }
    }

    private static final Map<String, ZoneId> ZONES = new ConcurrentHashMap<>();

    public PeriodClock {
        resetHour = Math.floorMod(resetHour, 24);
    }

    /**
     * A clock from configured values.
     *
     * @param timezone  an IANA id; blank, {@code null} or unknown means the server's
     * @param resetHour the hour a day starts at, wrapped into 0-23
     * @param weekStart the day a week starts on
     */
    public static PeriodClock of(String timezone, int resetHour, DayOfWeek weekStart) {
        return new PeriodClock(zone(timezone), resetHour, weekStart);
    }

    /** The zone a configured id names, or the server's own when blank or unknown. */
    public static ZoneId zone(String configured) {
        String id = configured == null ? "" : configured.trim();
        return ZONES.computeIfAbsent(id, PeriodClock::parseZone);
    }

    /** Now, in this clock's zone. */
    public ZonedDateTime now() {
        return ZonedDateTime.now(zone);
    }

    /** The day the period a moment falls in started on. */
    public LocalDate firstDay(Period period, ZonedDateTime moment) {
        LocalDate day = moment.withZoneSameInstant(zone).minusHours(resetHour).toLocalDate();
        return switch (period) {
            case DAILY -> day;
            case WEEKLY -> day.with(TemporalAdjusters.previousOrSame(weekStart));
            case MONTHLY -> day.withDayOfMonth(1);
        };
    }

    /** When the period a moment falls in began, in epoch milliseconds. */
    public long startsAt(Period period, ZonedDateTime moment) {
        return at(firstDay(period, moment));
    }

    /** When the period a moment falls in ends, in epoch milliseconds. */
    public long endsAt(Period period, ZonedDateTime moment) {
        return at(firstDay(period, moment).plus(1, period.unit));
    }

    /** How long is left of the current period, in milliseconds. */
    public long remaining(Period period) {
        return Math.max(0L, endsAt(period, now()) - System.currentTimeMillis());
    }

    /** The current period's name: its first day, as {@code 2026-09-14}. */
    public String key(Period period) {
        return key(period, now());
    }

    /** The name of the period a moment falls in, for a moment other than now. */
    public String key(Period period, ZonedDateTime moment) {
        return firstDay(period, moment).toString();
    }

    /** The name of the period before the current one. */
    public String previousKey(Period period) {
        return firstDay(period, now()).minus(1, period.unit).toString();
    }

    private long at(LocalDate day) {
        return day.atStartOfDay(zone).plusHours(resetHour).toInstant().toEpochMilli();
    }

    private static ZoneId parseZone(String id) {
        if (id.isEmpty()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(id);
        } catch (DateTimeException unknown) {
            return ZoneId.systemDefault();
        }
    }
}
