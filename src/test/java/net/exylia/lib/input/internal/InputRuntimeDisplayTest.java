package net.exylia.lib.input.internal;

import net.exylia.lib.input.InputParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a prefilled box shows has to be what the box accepts.
 *
 * <p>A duration default went in as {@code Duration.toString()}, so an admin
 * opening a timing field read {@code PT1M} and had to retype it before the
 * field would take an answer.
 */
class InputRuntimeDisplayTest {

    private static Duration roundTrip(Duration duration) {
        String shown = InputRuntime.display(duration);
        InputParser.Parsed<Duration> parsed = InputParser.duration().parse(shown);
        assertTrue(parsed.ok(), () -> shown + " should have parsed back, got: " + parsed.error());
        return parsed.value();
    }

    @Test
    @DisplayName("A duration is shown the way a duration is typed")
    void showsDurationsAsText() {
        assertEquals("1m", InputRuntime.display(Duration.ofMinutes(1)));
        assertEquals("1h 30m", InputRuntime.display(Duration.ofMinutes(90)));
        assertEquals("45s", InputRuntime.display(Duration.ofSeconds(45)));
        assertEquals("250ms", InputRuntime.display(Duration.ofMillis(250)));
    }

    @Test
    @DisplayName("Whatever is shown parses back to the same duration")
    void survivesTheRoundTrip() {
        for (Duration duration : new Duration[]{
                Duration.ofSeconds(1), Duration.ofSeconds(45), Duration.ofMinutes(1),
                Duration.ofMinutes(90), Duration.ofHours(5), Duration.ofDays(3),
                Duration.ofMillis(250), Duration.ZERO}) {
            assertEquals(duration, roundTrip(duration));
        }
    }

    @Test
    @DisplayName("A decimal is written plain, never in scientific notation")
    void showsDecimalsPlain() {
        assertEquals("100", InputRuntime.display(new java.math.BigDecimal("100.00").stripTrailingZeros()));
        assertEquals("100", InputRuntime.display(new java.math.BigDecimal("1E+2")));
        assertEquals("0.5", InputRuntime.display(new java.math.BigDecimal("0.50")));
        assertEquals("0", InputRuntime.display(java.math.BigDecimal.ZERO.setScale(2)));
    }

    @Test
    @DisplayName("Everything else is written as it always was")
    void leavesOtherValuesAlone() {
        assertEquals("", InputRuntime.display(null));
        assertEquals("7", InputRuntime.display(7));
        assertEquals("true", InputRuntime.display(true));
        assertEquals("arena", InputRuntime.display("arena"));
    }
}
