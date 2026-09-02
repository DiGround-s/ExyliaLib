package net.exylia.lib.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a length of time is written for a player.
 *
 * <p>The library's one implementation, shared by cooldowns, countdowns and
 * {@code %time%}.
 */
class TimeFormatsTest {

    // ------------------------------------------------------------------
    // Tenths — the point of the whole thing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tenths show one decimal")
    void tenths() {
        assertEquals("3.3", TimeFormats.render(3.3, TimeFormats.Style.TENTHS));
        assertEquals("0.5", TimeFormats.render(0.5, TimeFormats.Style.TENTHS));
        assertEquals("16.0", TimeFormats.render(16.0, TimeFormats.Style.TENTHS));
    }

    @Test
    @DisplayName("rounding is half-up, so a countdown never looks stalled")
    void roundingIsHalfUp() {
        // Java's default half-even renders this as 0.2, which reads as the
        // countdown stopping when it passes the point.
        assertEquals("0.3", TimeFormats.render(0.25, TimeFormats.Style.TENTHS));
        assertEquals("3.4", TimeFormats.render(3.35, TimeFormats.Style.TENTHS));
    }

    @Test
    @DisplayName("hundredths show two decimals")
    void hundredths() {
        assertEquals("3.34", TimeFormats.render(3.342, TimeFormats.Style.HUNDREDTHS));
    }

    @Test
    @DisplayName("the decimal separator is a dot regardless of the server's locale")
    void separatorIsFixed() {
        // A host in Europe would otherwise render 3,3 from the same config
        // that renders 3.3 elsewhere.
        assertEquals("3.3", TimeFormats.render(3.3, TimeFormats.Style.TENTHS));
    }

    // ------------------------------------------------------------------
    // Auto
    // ------------------------------------------------------------------

    @Test
    @DisplayName("auto shows tenths under ten seconds, where a player reacts to them")
    void autoShowsTenthsWhenClose() {
        assertEquals("3.3", TimeFormats.render(3.3, TimeFormats.Style.AUTO));
        assertEquals("9.9", TimeFormats.render(9.9, TimeFormats.Style.AUTO));
    }

    @Test
    @DisplayName("auto drops the decimal it does not need")
    void autoDropsDecimals() {
        assertEquals("42", TimeFormats.render(42.7, TimeFormats.Style.AUTO));
        assertEquals("10", TimeFormats.render(10.0, TimeFormats.Style.AUTO));
    }

    @Test
    @DisplayName("auto switches to a clock past a minute")
    void autoUsesClock() {
        assertEquals("1:35", TimeFormats.render(95.0, TimeFormats.Style.AUTO));
    }

    // ------------------------------------------------------------------
    // Clock and full
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a clock pads its seconds")
    void clockPads() {
        assertEquals("1:05", TimeFormats.render(65.0, TimeFormats.Style.CLOCK));
        assertEquals("0:09", TimeFormats.render(9.0, TimeFormats.Style.CLOCK));
    }

    @Test
    @DisplayName("a clock grows an hours field when it needs one")
    void clockShowsHours() {
        assertEquals("1:00:00", TimeFormats.render(3600.0, TimeFormats.Style.CLOCK));
        assertEquals("2:05:03", TimeFormats.render(7503.0, TimeFormats.Style.CLOCK));
    }

    @Test
    @DisplayName("full spells out the parts that are there")
    void full() {
        assertEquals("1h 1m 5s", TimeFormats.render(3665.0, TimeFormats.Style.FULL));
        assertEquals("5m", TimeFormats.render(300.0, TimeFormats.Style.FULL));
        assertEquals("45s", TimeFormats.render(45.0, TimeFormats.Style.FULL));
    }

    @Test
    @DisplayName("full rolls into days rather than counting hours forever")
    void fullRollsIntoDays() {
        assertEquals("5d", TimeFormats.render(432_000.0, TimeFormats.Style.FULL));
        assertEquals("1d", TimeFormats.render(86_400.0, TimeFormats.Style.FULL));
        assertEquals("2d 3h 4m 5s", TimeFormats.render(183_845.0, TimeFormats.Style.FULL));
        assertEquals("23h 59m 59s", TimeFormats.render(86_399.0, TimeFormats.Style.FULL));
    }

    @Test
    @DisplayName("full still says something when there is nothing left")
    void fullOfZero() {
        assertEquals("0s", TimeFormats.render(0.0, TimeFormats.Style.FULL));
    }

    // ------------------------------------------------------------------
    // Edges
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a finished countdown reads as zero, never as a negative")
    void negativeIsZero() {
        assertEquals("0.0", TimeFormats.render(-1.2, TimeFormats.Style.TENTHS));
        assertEquals("0", TimeFormats.render(-5.0, TimeFormats.Style.SECONDS));
    }

    @Test
    @DisplayName("a number that is not one reads as zero")
    void nonFiniteIsZero() {
        assertEquals("0.0", TimeFormats.render(Double.NaN, TimeFormats.Style.TENTHS));
        assertEquals("0.0", TimeFormats.render(Double.POSITIVE_INFINITY,
                TimeFormats.Style.TENTHS));
    }

    @Test
    @DisplayName("seconds are floored, not rounded")
    void secondsFloor() {
        assertEquals("3", TimeFormats.render(3.9, TimeFormats.Style.SECONDS));
    }

    // ------------------------------------------------------------------
    // Durations and named styles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a duration renders like the seconds it holds")
    void fromDuration() {
        assertEquals("3.3", TimeFormats.render(Duration.ofMillis(3300),
                TimeFormats.Style.TENTHS));
        assertEquals("1:30", TimeFormats.render(Duration.ofSeconds(90),
                TimeFormats.Style.CLOCK));
    }

    @Test
    @DisplayName("a style can be named the way a config would name it")
    void namedStyles() {
        assertEquals(TimeFormats.Style.TENTHS, TimeFormats.styleOf("tenths"));
        assertEquals(TimeFormats.Style.TENTHS, TimeFormats.styleOf("1"));
        assertEquals(TimeFormats.Style.SECONDS, TimeFormats.styleOf("s"));
        assertEquals(TimeFormats.Style.CLOCK, TimeFormats.styleOf("CLOCK"));
    }

    @Test
    @DisplayName("an unknown style falls back rather than failing")
    void unknownStyleFallsBack() {
        // A typo in a config should not stop a boss bar from drawing.
        assertEquals(TimeFormats.Style.AUTO, TimeFormats.styleOf("nonsense"));
        assertEquals(TimeFormats.Style.AUTO, TimeFormats.styleOf(null));
        assertEquals("3.3", TimeFormats.render(3.3, "nonsense"));
    }
}
