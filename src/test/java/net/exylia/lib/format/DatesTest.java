package net.exylia.lib.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a date is written for a player.
 *
 * <p>Every absolute assertion names its zone, because the machine running this
 * is not in the same one as the machine that will run it next and a date is the
 * one value where that shows.
 */
class DatesTest {

    /** Monday, 17 August 2026, 14:30:05 UTC. */
    private static final Instant MOMENT = Instant.parse("2026-08-17T14:30:05Z");

    private static final ZoneId UTC = ZoneOffset.UTC;

    // ------------------------------------------------------------------
    // Styles — the exact strings the ecosystem's configs will see
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ISO sorts as text, which is what a log line needs")
    void iso() {
        assertEquals("2026-08-17", Dates.format(MOMENT, Dates.Style.ISO, UTC));
    }

    @Test
    @DisplayName("ISO_TIME carries the time and still sorts")
    void isoTime() {
        assertEquals("2026-08-17 14:30:05", Dates.format(MOMENT, Dates.Style.ISO_TIME, UTC));
    }

    @Test
    @DisplayName("DATE writes the day first, as the ecosystem does")
    void date() {
        assertEquals("17/08/2026", Dates.format(MOMENT, Dates.Style.DATE, UTC));
    }

    @Test
    @DisplayName("TIME is a twenty-four hour clock without seconds")
    void time() {
        assertEquals("14:30", Dates.format(MOMENT, Dates.Style.TIME, UTC));
    }

    @Test
    @DisplayName("TIME_SECONDS keeps the seconds")
    void timeSeconds() {
        assertEquals("14:30:05", Dates.format(MOMENT, Dates.Style.TIME_SECONDS, UTC));
    }

    @Test
    @DisplayName("SHORT drops the year for a column that does not need it")
    void shortStyle() {
        assertEquals("17 Aug", Dates.format(MOMENT, Dates.Style.SHORT, UTC));
    }

    @Test
    @DisplayName("LONG spells the month out")
    void longStyle() {
        assertEquals("17 August 2026", Dates.format(MOMENT, Dates.Style.LONG, UTC));
    }

    @Test
    @DisplayName("FULL spells the weekday out too")
    void fullStyle() {
        assertEquals("Monday, 17 August 2026", Dates.format(MOMENT, Dates.Style.FULL, UTC));
    }

    @Test
    @DisplayName("a caller-supplied pattern is the escape hatch, not the habit")
    void customPattern() {
        assertEquals("2026/08/17 14h30", Dates.format(MOMENT, "yyyy/MM/dd HH'h'mm", UTC));
    }

    @Test
    @DisplayName("a pattern the JDK cannot read falls back rather than taking a menu down")
    void brokenPatternFallsBack() {
        // Visibly an ISO timestamp instead of what the config asked for, which
        // is what gets the config fixed. Throwing would break the whole render.
        assertEquals("2026-08-17 14:30:05", Dates.format(MOMENT, "yyyy-MM-dd'", UTC));
    }

    @Test
    @DisplayName("a pattern is compiled once and handed back, not rebuilt per call")
    void patternsAreShared() {
        assertSame(Dates.pattern("dd-MM-yyyy"), Dates.pattern("dd-MM-yyyy"));
    }

    @Test
    @DisplayName("a style's formatter is built once, when the class loads")
    void styleFormattersAreShared() {
        for (Dates.Style style : Dates.Style.values()) {
            assertNotNull(style.formatter());
            assertSame(style.formatter(), style.formatter(),
                    style + " must hand back the same formatter every time");
        }
    }

    // ------------------------------------------------------------------
    // Units — named, never guessed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("milliseconds and seconds are different methods, not a heuristic")
    void unitsAreExplicit() {
        // ExyliaCommons read anything under ten billion as seconds, so this
        // millisecond timestamp — 1970-01-05, comfortably under the threshold —
        // came out as a date in 1980. Here the unit is in the method name.
        long millis = 345_600_000L;
        assertEquals("1970-01-05", Dates.formatMillis(millis, Dates.Style.ISO, UTC));
        assertEquals("1980-12-14", Dates.format(Dates.ofSeconds(millis), Dates.Style.ISO, UTC));
    }

    @Test
    @DisplayName("a zoneless date is written as it stands, not converted")
    void localDateTime() {
        LocalDateTime when = LocalDateTime.of(2026, 8, 17, 14, 30, 5);
        assertEquals("17/08/2026", Dates.format(when, Dates.Style.DATE));
        assertEquals("14:30:05", Dates.format(when, Dates.Style.TIME_SECONDS));
        assertEquals("Monday, 17 August 2026", Dates.format(when, Dates.Style.FULL));
    }

    // ------------------------------------------------------------------
    // Timezone
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a named zone moves the date, which is the point of naming one")
    void explicitZone() {
        assertEquals("2026-08-17 23:30:05",
                Dates.format(MOMENT, Dates.Style.ISO_TIME, ZoneId.of("Asia/Tokyo")));
        assertEquals("2026-08-17 07:30:05",
                Dates.format(MOMENT, Dates.Style.ISO_TIME, ZoneId.of("America/Los_Angeles")));
    }

    @Test
    @DisplayName("without a zone it is the server's own, so it matches the wall clock")
    void defaultZoneIsTheServers() {
        String expected = LocalDateTime.ofInstant(MOMENT, ZoneId.systemDefault())
                .format(Dates.Style.ISO_TIME.formatter());
        assertEquals(expected, Dates.format(MOMENT, Dates.Style.ISO_TIME));
        assertEquals(expected, Dates.formatMillis(MOMENT.toEpochMilli(), Dates.Style.ISO_TIME));
    }

    // ------------------------------------------------------------------
    // Relative
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the past reads as ago")
    void past() {
        Instant now = MOMENT;
        assertEquals("3d ago", Dates.relative(now, now.minusSeconds(3 * 86_400)));
        assertEquals("2h ago", Dates.relative(now, now.minusSeconds(7_200)));
        assertEquals("45s ago", Dates.relative(now, now.minusSeconds(45)));
    }

    @Test
    @DisplayName("the future reads as in")
    void future() {
        Instant now = MOMENT;
        assertEquals("in 2h", Dates.relative(now, now.plusSeconds(7_200)));
        assertEquals("in 3d", Dates.relative(now, now.plusSeconds(3 * 86_400)));
        assertEquals("in 30s", Dates.relative(now, now.plusSeconds(30)));
    }

    @Test
    @DisplayName("the largest unit that says something, up to years")
    void unitsClimb() {
        Instant now = MOMENT;
        assertEquals("30s ago", Dates.relative(now, now.minusSeconds(30)));
        assertEquals("5m ago", Dates.relative(now, now.minusSeconds(300)));
        assertEquals("3h ago", Dates.relative(now, now.minusSeconds(10_800)));
        assertEquals("2d ago", Dates.relative(now, now.minusSeconds(2 * 86_400)));
        assertEquals("2w ago", Dates.relative(now, now.minusSeconds(14 * 86_400)));
        assertEquals("2mo ago", Dates.relative(now, now.minusSeconds(60 * 86_400)));
        assertEquals("1y ago", Dates.relative(now, now.minusSeconds(365 * 86_400)));
    }

    @Test
    @DisplayName("a half unit keeps its decimal, because rounding it down is a lie")
    void halfUnits() {
        Instant now = MOMENT;
        // Telling a player they have two hours when they have two and a half is
        // a smaller error than the reverse, and both are avoidable.
        assertEquals("2.5h ago", Dates.relative(now, now.minusSeconds(9_000)));
        assertEquals("in 1.5m", Dates.relative(now, now.plusSeconds(90)));
    }

    @Test
    @DisplayName("anything inside ten seconds is just now, in either direction")
    void justNow() {
        Instant now = MOMENT;
        assertEquals("just now", Dates.relative(now, now));
        assertEquals("just now", Dates.relative(now, now.minusSeconds(9)));
        assertEquals("just now", Dates.relative(now, now.plusSeconds(9)));
        assertEquals("just now", Dates.relative(now, now.minusMillis(500)));
    }

    @Test
    @DisplayName("the threshold is ten seconds, not a minute, so an expiry stays honest")
    void justNowBoundary() {
        Instant now = MOMENT;
        // A mail expiring in forty seconds must not read "just now".
        assertEquals("in 10s", Dates.relative(now, now.plusSeconds(10)));
        assertEquals("10s ago", Dates.relative(now, now.minusSeconds(10)));
        assertEquals("in 40s", Dates.relative(now, now.plusSeconds(40)));
    }

    @Test
    @DisplayName("relative against now needs no zone and still reads as the past")
    void relativeAgainstNow() {
        long threeDaysAgo = System.currentTimeMillis() - 3L * 86_400 * 1000;
        assertEquals("3d ago", Dates.relativeMillis(threeDaysAgo));
        assertEquals("just now", Dates.relativeMillis(System.currentTimeMillis()));
        assertEquals("3d ago", Dates.relativeSeconds(threeDaysAgo / 1000));
    }

    @Test
    @DisplayName("a zero timestamp is 1970, not a missing value this decides to hide")
    void epochIsADate() {
        String rendered = Dates.relativeMillis(0L);
        assertTrue(rendered.endsWith("y ago"),
                "expected an age in years, got: " + rendered);
    }

    // ------------------------------------------------------------------
    // Locale
    // ------------------------------------------------------------------

    @Test
    @DisplayName("month and weekday names ignore the server's locale")
    void localeIsFixed() {
        Locale original = Locale.getDefault();
        try {
            // A host in Spain would otherwise render "lunes, 17 agosto 2026"
            // from the config that renders "Monday, 17 August 2026" elsewhere.
            Locale.setDefault(Locale.of("es", "ES"));
            assertEquals("Monday, 17 August 2026", Dates.format(MOMENT, Dates.Style.FULL, UTC));
            assertEquals("17 August 2026", Dates.format(MOMENT, Dates.Style.LONG, UTC));
            assertEquals("17 Aug", Dates.format(MOMENT, Dates.Style.SHORT, UTC));

            Locale.setDefault(Locale.of("de", "DE"));
            assertEquals("Monday, 17 August 2026", Dates.format(MOMENT, Dates.Style.FULL, UTC));

            Locale.setDefault(Locale.FRANCE);
            assertEquals("17 Aug", Dates.format(MOMENT, Dates.Style.SHORT, UTC));
            assertEquals("17/08/2026", Dates.format(MOMENT, Dates.Style.DATE, UTC));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("a pattern built under a European locale still renders English")
    void patternLocaleIsFixed() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("es", "ES"));
            assertEquals("August", Dates.format(MOMENT, "MMMM", UTC));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ------------------------------------------------------------------
    // Threading — the shared formatters are the whole performance story
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the shared formatters survive being hammered from several threads")
    void sharedFormattersAreThreadSafe() throws Exception {
        int threads = 8;
        int perThread = 2_000;
        String expected = Dates.format(MOMENT, Dates.Style.ISO_TIME, UTC);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        List<Throwable> failures = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();

        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < perThread; round++) {
                        seen.add(Dates.format(MOMENT, Dates.Style.ISO_TIME, UTC));
                        // The pattern cache is written from every thread here.
                        seen.add(Dates.format(MOMENT, "yyyy-MM-dd HH:mm:ss", UTC));
                    }
                } catch (Throwable failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                } finally {
                    done.countDown();
                }
            });
            workers.add(worker);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
        for (Thread worker : workers) {
            worker.join();
        }

        assertTrue(failures.isEmpty(), () -> "threads failed: " + failures);
        assertEquals(Set.of(expected), seen,
                "a shared formatter produced more than one answer for one instant");
    }

    @Test
    @DisplayName("relative is safe from several threads too")
    void relativeIsThreadSafe() throws Exception {
        int threads = 8;
        Instant now = MOMENT;
        Instant then = now.minusSeconds(3 * 86_400);

        CountDownLatch done = new CountDownLatch(threads);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        for (int index = 0; index < threads; index++) {
            new Thread(() -> {
                for (int round = 0; round < 2_000; round++) {
                    seen.add(Dates.relative(now, then));
                }
                done.countDown();
            }).start();
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
        assertEquals(Set.of("3d ago"), seen);
    }

    // ------------------------------------------------------------------
    // Robustness — a menu must render whatever the column held
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a negative timestamp is a date before 1970, not an error")
    void negativeTimestamp() {
        assertEquals("1969-12-31", Dates.formatMillis(-86_400_000L, Dates.Style.ISO, UTC));
    }

    @Test
    @DisplayName("no timestamp and no style can make a render throw")
    void nonsenseTimestampNeverThrows() {
        for (long nonsense : new long[]{Long.MIN_VALUE, Long.MAX_VALUE,
                -999_999_999_999_999L, 999_999_999_999_999L, -1L, 0L}) {
            for (Dates.Style style : Dates.Style.values()) {
                String rendered = assertDoesNotThrow(
                        () -> Dates.formatMillis(nonsense, style, UTC),
                        () -> nonsense + " threw on " + style);
                assertNotNull(rendered);
                assertTrue(!rendered.isEmpty(),
                        "an empty string reads as a plugin that forgot to fill the line in");
            }
        }
    }

    @Test
    @DisplayName("an absurd timestamp is printed absurdly, not tidied into a plausible date")
    void absurdTimestampIsVisible() {
        // Long.MAX_VALUE milliseconds is a real, writable instant. It renders as
        // the year it is, extra digits and all, because a date in the year
        // 292278994 reads as broken data at a glance — which is how it gets
        // fixed. Tidying it would be the magnitude guessing this class refuses.
        assertEquals("+292278994-08-17", Dates.formatMillis(Long.MAX_VALUE, Dates.Style.ISO, UTC));
        assertEquals("+292275056-05-16", Dates.formatMillis(Long.MIN_VALUE, Dates.Style.ISO, UTC));
    }

    @Test
    @DisplayName("a moment that cannot be written at all reads as unknown")
    void unwritableReadsAsUnknown() {
        // A second count this large leaves Instant's range, is clamped to its
        // edge, and that edge has no LocalDateTime. This is the only path that
        // reaches the word.
        assertEquals("unknown", Dates.formatSeconds(Long.MAX_VALUE, Dates.Style.ISO));
        assertEquals("unknown", Dates.formatSeconds(Long.MIN_VALUE, Dates.Style.ISO));
        assertEquals("unknown", Dates.format(Instant.MAX, Dates.Style.FULL, UTC));
        assertEquals("unknown", Dates.format(Instant.MIN, "yyyy", UTC));
    }

    @Test
    @DisplayName("a nonsense timestamp still reads as a length of time, never as a crash")
    void nonsenseRelative() {
        for (long nonsense : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L}) {
            String rendered = assertDoesNotThrow(() -> Dates.relativeMillis(nonsense),
                    () -> nonsense + " threw");
            assertTrue(rendered.endsWith(" ago") || rendered.startsWith("in ")
                            || rendered.equals("just now"),
                    "unexpected shape: " + rendered);
        }
    }

    @Test
    @DisplayName("the two ends of time do not overflow the gap between them")
    void extremeGap() {
        // Duration.toMillis() throws ArithmeticException on a gap this wide,
        // which is why the seconds field is read directly instead. The number
        // itself is only interesting in that it is a number and not a crash.
        assertDoesNotThrow(() -> Dates.relative(Instant.MIN, Instant.MAX));
        assertEquals("in 2001328768.1y", Dates.relative(Instant.MIN, Instant.MAX));
    }
}
