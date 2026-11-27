package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountUpTest {

    @Test
    void findsTheNumberOutsideTagsAndKeepsHowItIsWritten() {
        CountUp.Token t = CountUp.find("<#8a51c4>{icon:gold} Coins 1.2K");
        assertEquals(1200, t.value(), 1e-9);
        assertEquals("K", t.suffix());
        assertEquals(1, t.decimals());
        assertEquals("1.2K", "<#8a51c4>{icon:gold} Coins 1.2K".substring(t.start(), t.end()));
        assertEquals(1234567, CountUp.find("{info}1,234,567").value(), 1e-9);
        assertTrue(CountUp.find("{info}1,234,567").grouped());
        assertNull(CountUp.find("{success}Ready"));
        assertEquals("2,500", CountUp.format(2500.9, CountUp.find("1,000")));
        assertEquals("3.4M", CountUp.format(3_456_000, CountUp.find("1.0M")));
    }

    @Test
    void stepsFromTheOldValueAndNeverCountsTheFirstDraw() {
        Map<String, CountUp.Count> counts = CountUp.store();
        boolean[] going = new boolean[1];
        assertEquals("Coins 100", CountUp.step(counts, "c", "Coins 100", 8, 0, false, going));
        assertFalse(going[0]);
        // The value moves: the first step shows the old one, then it climbs, then it lands.
        assertEquals("Coins 100", CountUp.step(counts, "c", "Coins 200", 8, 1000, false, going));
        assertTrue(going[0]);
        String mid = CountUp.step(counts, "c", "Coins 200", 8, 1200, false, going);
        int shown = Integer.parseInt(mid.substring(6));
        assertTrue(shown > 100 && shown < 200, mid);
        assertEquals("Coins 200", CountUp.step(counts, "c", "Coins 200", 8, 1400, false, going));
        assertFalse(going[0]);
        // Reduced motion jumps.
        assertEquals("Coins 900", CountUp.step(counts, "c", "Coins 900", 8, 2000, true, going));
        assertFalse(going[0]);
    }
}
