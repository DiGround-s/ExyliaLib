package net.exylia.lib.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FortuneTest {

    @Test
    void vanillaAveragesTheOreFormula() {
        long total = 0;
        int rolls = 40_000;
        for (int i = 0; i < rolls; i++) {
            int amount = Fortune.apply(Fortune.Mode.VANILLA, 1, 3, 0);
            assertTrue(amount >= 1 && amount <= 4);
            total += amount;
        }
        double average = (double) total / rolls;
        assertTrue(average > 2.15 && average < 2.25, "average " + average);
    }

    @Test
    void linearAndChance() {
        assertEquals(4, Fortune.apply(Fortune.Mode.LINEAR, 2, 2, 0.5));
        assertEquals(5, Fortune.apply(Fortune.Mode.OFF, 5, 3, 1));
        assertEquals(5, Fortune.apply(Fortune.Mode.CHANCE, 5, 3, 1));
        assertEquals(0.8, Fortune.chance(0.5, 3, 0.2), 1e-9);
        assertEquals(100.0, Fortune.chance(80, 3, 1), 1e-9);
        assertEquals(0.5, Fortune.chance(0.5, 3, 0), 1e-9);
        assertEquals(1.75, Fortune.factor(Fortune.Mode.LINEAR, 3, 0.25), 1e-9);
    }
}
