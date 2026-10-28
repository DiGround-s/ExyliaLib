package net.exylia.lib.util;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Periods start at the reset hour in the configured zone, the same wherever they are asked. */
class PeriodClockTest {

    private static final PeriodClock CLOCK = PeriodClock.of("Europe/Madrid", 5, DayOfWeek.MONDAY);

    private static ZonedDateTime madrid(int day, int hour) {
        return ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, ZoneId.of("Europe/Madrid"));
    }

    @Test
    void aDayStartsAtTheResetHour() {
        assertEquals("2026-10-26", CLOCK.key(PeriodClock.Period.DAILY, madrid(27, 4)));
        assertEquals("2026-10-27", CLOCK.key(PeriodClock.Period.DAILY, madrid(27, 5)));
    }

    @Test
    void theSameMomentNamesTheSamePeriodFromAnotherZone() {
        ZonedDateTime tokyo = madrid(27, 6).withZoneSameInstant(ZoneId.of("Asia/Tokyo"));
        assertEquals("2026-10-27", CLOCK.key(PeriodClock.Period.DAILY, tokyo));
    }

    @Test
    void aWeekStartsOnItsDay() {
        // 2026-10-26 is a Monday; Monday before the reset hour still belongs to the week before.
        assertEquals("2026-10-26", CLOCK.key(PeriodClock.Period.WEEKLY, madrid(29, 12)));
        assertEquals("2026-10-19", CLOCK.key(PeriodClock.Period.WEEKLY, madrid(26, 4)));
    }

    @Test
    void aPeriodEndsWhereTheNextStarts() {
        ZonedDateTime moment = madrid(27, 12);
        assertEquals(CLOCK.endsAt(PeriodClock.Period.DAILY, moment),
                CLOCK.startsAt(PeriodClock.Period.DAILY, moment.plusDays(1)));
        assertEquals(madrid(28, 5).toInstant().toEpochMilli(), CLOCK.endsAt(PeriodClock.Period.DAILY, moment));
    }

    @Test
    void anUnknownZoneIsTheServers() {
        assertEquals(ZoneId.systemDefault(), PeriodClock.zone("Not/AZone"));
        assertEquals(ZoneId.systemDefault(), PeriodClock.zone(""));
    }
}
