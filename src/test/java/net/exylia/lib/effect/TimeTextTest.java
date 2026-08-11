package net.exylia.lib.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a timer's seconds are written for a player, and how durations are parsed
 * from config.
 */
class TimeTextTest {

    private static String render(double seconds, String style) throws Exception {
        Class<?> type = Class.forName("net.exylia.lib.effect.internal.TimeFormats");
        Method method = type.getDeclaredMethod("render", double.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, seconds, style);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a short countdown shows tenths, which is the point of decimals")
    void shortCountdownShowsTenths() throws Exception {
        assertEquals("3.3", render(3.3, "auto"));
        assertEquals("0.5", render(0.5, "auto"));
    }

    @Test
    @DisplayName("a longer countdown drops the decimal it does not need")
    void longerCountdownDropsDecimals() throws Exception {
        assertEquals("42", render(42.7, "auto"));
    }

    @Test
    @DisplayName("past a minute the time reads as a clock")
    void pastAMinuteReadsAsAClock() throws Exception {
        assertEquals("1:30", render(90, "auto"));
        assertEquals("2:05", render(125, "auto"));
    }

    @Test
    @DisplayName("an hour is written with hours")
    void hoursAreWritten() throws Exception {
        assertEquals("1:05:03", render(3903, "clock"));
    }

    @Test
    @DisplayName("each named style writes what it says")
    void namedStyles() throws Exception {
        assertEquals("3", render(3.7, "seconds"));
        assertEquals("3.7", render(3.7, "tenths"));
        assertEquals("3.70", render(3.7, "hundredths"));
        assertEquals("0:03", render(3.7, "clock"));
        assertEquals("3s", render(3.7, "full"));
    }

    @Test
    @DisplayName("rounding goes up, so a countdown never appears to stall")
    void roundingGoesUp() throws Exception {
        // Half-even would render this as 0.2 and the countdown would look stuck.
        assertEquals("0.3", render(0.25, "tenths"));
    }

    @Test
    @DisplayName("a finished timer shows zero rather than a negative")
    void finishedShowsZero() throws Exception {
        assertEquals("0.0", render(-1, "tenths"));
        assertEquals("0.0", render(0, "auto"));
    }

    @Test
    @DisplayName("an unknown style falls back rather than failing")
    void unknownStyleFallsBack() throws Exception {
        assertEquals(render(5, "auto"), render(5, "nonsense"));
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a bare number is read as seconds")
    void bareNumberIsSeconds() {
        assertEquals(60, Ticks.parse("3", -1));
        assertEquals(66, Ticks.parse("3.3", -1));
    }

    @Test
    @DisplayName("units are understood")
    void unitsAreUnderstood() {
        assertEquals(66, Ticks.parse("3.3s", -1));
        assertEquals(10, Ticks.parse("500ms", -1));
        assertEquals(40, Ticks.parse("40t", -1));
        assertEquals(1200, Ticks.parse("1m", -1));
        assertEquals(72000, Ticks.parse("1h", -1));
    }

    @Test
    @DisplayName("nonsense falls back instead of throwing")
    void nonsenseFallsBack() {
        assertEquals(-1, Ticks.parse("soon", -1));
        assertEquals(-1, Ticks.parse("", -1));
        assertEquals(-1, Ticks.parse(null, -1));
        assertEquals(-1, Ticks.parse("5 parsecs", -1));
    }

    @Test
    @DisplayName("seconds and ticks convert both ways without drifting")
    void conversionsAgree() {
        assertEquals(66, Ticks.fromSeconds(3.3));
        assertEquals(3.3, Ticks.toSeconds(66), 0.0001);
        assertEquals(0, Ticks.fromSeconds(-5), "a negative duration is no duration");
        assertEquals(2, Ticks.fromSeconds(0.09), "a short effect must not vanish");
    }
}
