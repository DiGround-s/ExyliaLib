package net.exylia.lib.schedule.internal;

import net.exylia.lib.schedule.Schedule;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * When a schedule next fires.
 *
 * <p>Pure arithmetic over the calendar: no Bukkit, no state, no clock of its
 * own. That is what lets the runtime do this once per fire, off the main
 * thread, and then compare one long per second instead of re-deciding whether
 * every entry matches the current minute.
 *
 * <h2>Why not "does it match right now"</h2>
 * The scheduler this replaces woke every second, walked every entry, compared
 * hours and minutes, and kept a map of the last minute each one fired in so a
 * second tick inside the same minute would not fire it twice. That is a scan
 * per second forever, plus a correctness trick to paper over it. Computing the
 * instant instead makes the idle case a single {@code long} comparison and
 * removes the duplicate-fire question entirely.
 *
 * @since 1.70.0
 */
public final class NextFire {

    /** How far ahead to look before giving up: a week plus the current day. */
    private static final int HORIZON_DAYS = 8;

    private NextFire() {
        throw new AssertionError("No instances.");
    }

    /**
     * The first moment strictly after {@code after} that a schedule fires.
     *
     * @param schedule the schedule
     * @param after    the moment to look from, in the schedule's own zone
     * @return the moment, or nothing when it never fires
     */
    public static @NotNull Optional<ZonedDateTime> after(@NotNull Schedule schedule,
                                                         @NotNull ZonedDateTime after) {
        if (!schedule.isRunnable()) {
            return Optional.empty();
        }
        return schedule.isInterval() ? nextInterval(schedule, after) : nextFixed(schedule, after);
    }

    /**
     * The same, as epoch milliseconds, for a runtime that compares longs.
     *
     * @param schedule the schedule
     * @param after    the moment to look from, as epoch milliseconds
     * @param zone     which calendar the schedule is written in
     * @return the moment, or {@link Long#MAX_VALUE} when it never fires
     */
    public static long afterMillis(@NotNull Schedule schedule, long after, @NotNull ZoneId zone) {
        ZonedDateTime from = java.time.Instant.ofEpochMilli(after).atZone(zone);
        return after(schedule, from)
                .map(when -> when.toInstant().toEpochMilli())
                .orElse(Long.MAX_VALUE);
    }

    private static Optional<ZonedDateTime> nextFixed(Schedule schedule, ZonedDateTime after) {
        ZoneId zone = after.getZone();
        for (int ahead = 0; ahead < HORIZON_DAYS; ahead++) {
            LocalDate date = after.toLocalDate().plusDays(ahead);
            if (!schedule.matchesDay(date.getDayOfWeek())) {
                continue;
            }
            // The times are sorted by the record itself, so the first one past
            // the cursor is the earliest one.
            for (LocalTime time : schedule.times()) {
                ZonedDateTime candidate = date.atTime(time).atZone(zone);
                if (candidate.isAfter(after)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<ZonedDateTime> nextInterval(Schedule schedule, ZonedDateTime after) {
        Duration every = schedule.every();
        if (every == null || every.isZero() || every.isNegative()) {
            return Optional.empty();
        }
        ZoneId zone = after.getZone();
        LocalTime windowStart = schedule.windowStart();
        LocalTime windowEnd = schedule.windowEnd();
        // A window written backwards — 23:00 to 02:00 — would otherwise fire
        // nothing at all and look like a broken schedule rather than a typo.
        if (windowEnd.isBefore(windowStart)) {
            return Optional.empty();
        }
        long step = every.toMillis();

        for (int ahead = 0; ahead < HORIZON_DAYS; ahead++) {
            LocalDate date = after.toLocalDate().plusDays(ahead);
            if (!schedule.matchesDay(date.getDayOfWeek())) {
                continue;
            }
            ZonedDateTime opens = date.atTime(windowStart).atZone(zone);
            ZonedDateTime closes = date.atTime(windowEnd).atZone(zone);
            ZonedDateTime candidate;
            if (!opens.isAfter(after)) {
                // Anchored to the window's own opening rather than to now, so a
                // reload at 13:07 does not move an every-hour schedule off the
                // hour it has been firing on all day.
                long elapsed = opens.until(after, java.time.temporal.ChronoUnit.MILLIS);
                long steps = elapsed / step + 1;
                candidate = opens.plus(Duration.ofMillis(steps * step));
            } else {
                candidate = opens;
            }
            if (!candidate.isAfter(closes)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
