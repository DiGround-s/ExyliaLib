package net.exylia.lib.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One entry of a list, rolled by weight. */
class WeightedTest {

    private record Entry(String name, double weight) {
    }

    @Test
    void emptyListPicksNothing() {
        assertNull(Weighted.pick(List.<Entry>of(), Entry::weight));
    }

    @Test
    void rollsFollowTheWeights() {
        List<Entry> entries = List.of(new Entry("common", 3), new Entry("rare", 1));
        Map<String, Integer> counts = new HashMap<>();
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 40_000; i++) {
            counts.merge(Weighted.pick(entries, Entry::weight, random).name(), 1, Integer::sum);
        }
        double common = counts.get("common") / 40_000.0;
        assertEquals(0.75, common, 0.02);
    }

    @Test
    void zeroWeightIsNeverRolled() {
        List<Entry> entries = List.of(new Entry("never", 0), new Entry("always", 2), new Entry("negative", -5));
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 2_000; i++) {
            assertEquals("always", Weighted.pick(entries, Entry::weight, random).name());
        }
    }

    @Test
    void allZeroWeightsPickEvenly() {
        List<Entry> entries = List.of(new Entry("a", 0), new Entry("b", 0));
        SplittableRandom random = new SplittableRandom(3);
        boolean sawA = false;
        boolean sawB = false;
        for (int i = 0; i < 200; i++) {
            String name = Weighted.pick(entries, Entry::weight, random).name();
            sawA |= name.equals("a");
            sawB |= name.equals("b");
        }
        assertTrue(sawA && sawB);
    }
}
