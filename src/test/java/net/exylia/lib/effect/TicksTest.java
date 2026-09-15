package net.exylia.lib.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading and writing the lengths of time a server owner types.
 *
 * <p>The promise being kept here is that a bare number never changes meaning:
 * a key written in ticks keeps reading ticks, a key written in seconds keeps
 * reading seconds, and only a value carrying a unit is read as one.
 */
class TicksTest {

    @Test
    @DisplayName("a bare number is seconds, and a unit is read as written")
    void parseReadsSeconds() {
        assertEquals(20, Ticks.parse("1", 0));
        assertEquals(20, Ticks.parse("1s", 0));
        assertEquals(1, Ticks.parse("50ms", 0));
        assertEquals(40, Ticks.parse("40t", 0));
        assertEquals(1200, Ticks.parse("1m", 0));
        assertEquals(1800, Ticks.parse("1m30s", 0));
        assertEquals(7, Ticks.parse("nonsense", 7));
    }

    @Test
    @DisplayName("a key written in ticks keeps reading a bare number as ticks")
    void parseTicksKeepsItsUnit() {
        assertEquals(20, Ticks.parseTicks("20", 0), "what 161 deployed menus mean");
        assertEquals(20, Ticks.parseTicks("1s", 0));
        assertEquals(1800, Ticks.parseTicks("1m30s", 0));
        assertEquals(5, Ticks.parseTicks("", 5));
        assertEquals(5, Ticks.parseTicks("nonsense", 5));
    }

    @Test
    @DisplayName("a key written in seconds keeps its decimals")
    void parseSecondsKeepsDecimals() {
        assertEquals(0.5, Ticks.parseSeconds("0.5", 0));
        assertEquals(0.5, Ticks.parseSeconds("500ms", 0));
        assertEquals(90, Ticks.parseSeconds("1m30s", 0));
        assertEquals(3.5, Ticks.parseSeconds("nonsense", 3.5));
    }

    @Test
    @DisplayName("what is written back is what the parsers read")
    void writeRoundTrips() {
        assertEquals("1m30s", Ticks.write(90_000));
        assertEquals("2d", Ticks.write(172_800_000));
        assertEquals("500ms", Ticks.write(500));
        assertEquals("0s", Ticks.write(0));

        for (String written : new String[] {"1m30s", "2d", "500ms", "45s", "1h5m3s"}) {
            long millis = Ticks.toMillis(Ticks.parse(written, -1));
            assertEquals(written, Ticks.write(millis), written + " should survive a round trip");
        }
    }
}
