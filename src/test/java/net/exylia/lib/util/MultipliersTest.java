package net.exylia.lib.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipliersTest {

    @Test
    void wholeFactorsAreExact() {
        assertEquals(6, Multipliers.scale(3, 2.0));
        assertEquals(3, Multipliers.scale(3, 1.0));
        assertEquals(0, Multipliers.scale(0, 2.0));
    }

    @Test
    void fractionIsRolledAroundTheExactValue() {
        long total = 0;
        int rolls = 20_000;
        for (int i = 0; i < rolls; i++) {
            int scaled = Multipliers.scale(1, 1.2);
            assertTrue(scaled == 1 || scaled == 2);
            total += scaled;
        }
        double average = (double) total / rolls;
        assertTrue(average > 1.17 && average < 1.23, "average " + average);
    }

    @Test
    void formatDropsTrailingZeros() {
        assertEquals("2", Multipliers.format(2.0));
        assertEquals("1.2", Multipliers.format(1.2));
        assertEquals("1.25", Multipliers.format(1.25));
    }
}
