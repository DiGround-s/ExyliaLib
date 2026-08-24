package net.exylia.lib.util.loot;

import net.exylia.lib.util.loot.internal.LootRolls;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What comes up, and how often.
 *
 * <p>The dice are handed in, so every case here is exact rather than likely: a
 * test that opened a chest a hundred times to prove a fifty-percent line works
 * is a test that fails on Fridays.
 *
 * <p>Both readings of a weight are checked, because both are in production —
 * a chest rolls every line as a percentage, a survival-games refill picks one
 * line by share — and the numbers stored in the tables mean whichever the
 * caller asks for.
 */
class LootRollsTest {

    private static LootEntry entry(String name, double weight) {
        return LootEntry.item(name).id(name).weight(weight).build();
    }

    private static List<LootEntry> table() {
        return List.of(entry("A", 30.0), entry("B", 60.0), entry("C", 90.0));
    }

    // ------------------------------------------------------------------
    // Rolling every line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a line comes up when the roll lands under its weight")
    void independentUsesWeightAsPercentage() {
        // 29.9 < 30, 60 is not < 60, 89.9 < 90.
        List<LootEntry> rolled = LootRolls.independent(table(), false, dice(29.9, 60.0, 89.9));

        assertEquals(List.of("A", "C"), ids(rolled));
    }

    @Test
    @DisplayName("what came up keeps the order the file wrote it in")
    void independentKeepsOrder() {
        List<LootEntry> rolled = LootRolls.independent(table(), false, dice(0.0, 0.0, 0.0));

        assertEquals(List.of("A", "B", "C"), ids(rolled));
    }

    @Test
    @DisplayName("nothing coming up is a real answer for a spawner")
    void independentCanBeEmpty() {
        assertTrue(LootRolls.independent(table(), false, dice(99.0, 99.0, 99.0)).isEmpty());
    }

    @Test
    @DisplayName("a chest that would open empty forces one line instead")
    void independentForcesOne() {
        List<LootEntry> rolled = LootRolls.independent(table(), true, dice(99.0, 99.0, 99.0).forced(1));

        assertEquals(List.of("B"), ids(rolled));
    }

    @Test
    @DisplayName("the forced line is picked evenly, not by weight")
    void forcedIsUniform() {
        Dice dice = dice(99.0, 99.0, 99.0).forced(2);

        LootRolls.independent(table(), true, dice);

        // The forced pick asks for an index across the whole table, never for a
        // number to compare against a weight.
        assertEquals(List.of("0..2"), dice.ranges);
    }

    @Test
    @DisplayName("an empty table stays empty even when a line is demanded")
    void emptyTableForcesNothing() {
        assertTrue(LootRolls.independent(List.of(), true, dice()).isEmpty());
    }

    @Test
    @DisplayName("the result can be added to and does not alias the table")
    void resultIsMutable() {
        List<LootEntry> rolled = LootRolls.independent(table(), false, dice(0.0, 99.0, 99.0));

        Collections.shuffle(rolled);
        assertEquals(1, rolled.size());
    }

    // ------------------------------------------------------------------
    // Picking one line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a pick walks the weights as shares of their total")
    void pickIsCumulative() {
        // Total 180: [0,30) is A, [30,90) is B, [90,180) is C.
        assertEquals("A", LootRolls.pick(table(), dice(0.0)).id());
        assertEquals("A", LootRolls.pick(table(), dice(29.9)).id());
        assertEquals("B", LootRolls.pick(table(), dice(30.0)).id());
        assertEquals("B", LootRolls.pick(table(), dice(89.9)).id());
        assertEquals("C", LootRolls.pick(table(), dice(90.0)).id());
        assertEquals("C", LootRolls.pick(table(), dice(179.9)).id());
    }

    @Test
    @DisplayName("a line of no weight is never picked")
    void pickSkipsZeroWeight() {
        List<LootEntry> table = List.of(entry("A", 0.0), entry("B", 10.0));

        assertEquals("B", LootRolls.pick(table, dice(0.0)).id());
        assertEquals("B", LootRolls.pick(table, dice(9.9)).id());
    }

    @Test
    @DisplayName("a table nobody gave any weight picks nothing rather than guessing")
    void pickWithoutWeights() {
        assertNull(LootRolls.pick(List.of(entry("A", 0.0), entry("B", -5.0)), dice()));
        assertNull(LootRolls.pick(List.of(), dice()));
    }

    // ------------------------------------------------------------------
    // How many
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a range is rolled between its ends, both included")
    void amountRange() {
        LootEntry ranged = LootEntry.item("A").amountBetween(2, 5).build();
        Dice dice = dice().withAmount(4);

        assertEquals(4, LootRolls.amount(ranged, dice));
        assertEquals(List.of("2..5"), dice.ranges);
    }

    @Test
    @DisplayName("a fixed amount does not roll at all")
    void amountFixed() {
        assertEquals(3, LootRolls.amount(LootEntry.item("A").amount(3).build(), dice()));
    }

    @Test
    @DisplayName("an entry stored with no amount still gives one, not zero")
    void amountNeverZero() {
        LootEntry broken = LootEntry.item("A").minAmount(0).maxAmount(0).build();

        assertEquals(1, LootRolls.amount(broken, dice()));
    }

    @Test
    @DisplayName("bounds the wrong way round give the low end rather than nothing")
    void amountReversed() {
        LootEntry reversed = LootEntry.item("A").minAmount(5).maxAmount(2).build();

        assertEquals(5, LootRolls.amount(reversed, dice()));
    }

    // ------------------------------------------------------------------

    private static Dice dice(double... rolls) {
        return new Dice(rolls);
    }

    private static List<String> ids(List<LootEntry> entries) {
        List<String> ids = new ArrayList<>(entries.size());
        for (LootEntry entry : entries) {
            ids.add(entry.id());
        }
        return ids;
    }

    /** Dice that were decided in advance. */
    private static final class Dice implements LootRolls.Dice {

        private final Deque<Double> rolls = new ArrayDeque<>();
        private final List<String> ranges = new ArrayList<>();
        private int between = -1;

        private Dice(double... rolls) {
            for (double roll : rolls) {
                this.rolls.add(roll);
            }
        }

        private Dice forced(int index) {
            this.between = index;
            return this;
        }

        private Dice withAmount(int amount) {
            this.between = amount;
            return this;
        }

        @Override
        public double next(double bound) {
            Double roll = rolls.poll();
            if (roll == null) {
                throw new AssertionError("asked for a roll nobody arranged");
            }
            return roll;
        }

        @Override
        public int between(int min, int max) {
            ranges.add(min + ".." + max);
            return between >= 0 ? between : min;
        }

        @Override
        public void shuffle(List<?> list) {
            // The order a chest lays them out in is not what these tests are about.
        }
    }
}
