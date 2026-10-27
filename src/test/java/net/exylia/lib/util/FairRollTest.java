package net.exylia.lib.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A weighted roll that avoids recent results and guarantees a rare one after enough misses. */
class FairRollTest {

    private record Entry(String id, double weight, int tier) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("a", 100, 0), new Entry("b", 100, 0), new Entry("c", 100, 0),
            new Entry("epic", 0.0001, 2));

    private static FairRoll<Entry> roll() {
        return FairRoll.of(ENTRIES, Entry::weight, Entry::id, Entry::tier);
    }

    @Test
    void emptyListRollsNothing() {
        FairRoll.Result<Entry> result = FairRoll.of(List.<Entry>of(), Entry::weight, Entry::id, Entry::tier)
                .roll(FairRoll.Luck.NONE, new SplittableRandom(1));
        assertNull(result.picked());
        assertEquals(FairRoll.Luck.NONE, result.luck());
    }

    @Test
    void recentResultsAreNotRolledAgain() {
        FairRoll<Entry> roll = roll().avoidRecent(2);
        SplittableRandom random = new SplittableRandom(3);
        FairRoll.Luck luck = FairRoll.Luck.NONE;
        String previous = null;
        String beforeThat = null;
        for (int i = 0; i < 500; i++) {
            FairRoll.Result<Entry> result = roll.roll(luck, random);
            String id = result.picked().id();
            assertFalse(id.equals(previous) || id.equals(beforeThat), "repeated " + id);
            beforeThat = previous;
            previous = id;
            luck = result.luck();
            assertTrue(luck.recent().size() <= 2);
        }
    }

    @Test
    void avoidingEverythingStillRolls() {
        FairRoll<Entry> roll = FairRoll.of(List.of(new Entry("only", 1, 0)), Entry::weight, Entry::id, Entry::tier)
                .avoidRecent(3);
        FairRoll.Luck luck = new FairRoll.Luck(List.of("only"), java.util.Map.of());
        assertEquals("only", roll.roll(luck, new SplittableRandom(1)).picked().id());
    }

    @Test
    void pityForcesTheTierAfterEnoughMisses() {
        FairRoll<Entry> roll = roll().rules(List.of(new FairRoll.Rule("epic", 2, 5)));
        SplittableRandom random = new SplittableRandom(9);
        FairRoll.Luck luck = FairRoll.Luck.NONE;
        for (int i = 0; i < 5; i++) {
            FairRoll.Result<Entry> result = roll.roll(luck, random);
            assertNull(result.forced());
            assertEquals(0, result.picked().tier());
            luck = result.luck();
            assertEquals(i + 1, luck.misses("epic"));
        }
        FairRoll.Result<Entry> forced = roll.roll(luck, random);
        assertEquals("epic", forced.picked().id());
        assertNotNull(forced.forced());
        assertEquals(0, forced.luck().misses("epic"));
    }

    @Test
    void highestDueRuleWins() {
        List<Entry> entries = List.of(new Entry("common", 1, 0), new Entry("rare", 1, 1), new Entry("mythic", 1, 3));
        FairRoll<Entry> roll = FairRoll.of(entries, Entry::weight, Entry::id, Entry::tier)
                .rules(List.of(new FairRoll.Rule("rare", 1, 1), new FairRoll.Rule("mythic", 3, 1)));
        FairRoll.Luck luck = new FairRoll.Luck(List.of(), java.util.Map.of("rare", 1, "mythic", 1));
        FairRoll.Result<Entry> result = roll.roll(luck, new SplittableRandom(2));
        assertEquals("mythic", result.picked().id());
        assertEquals(0, result.luck().misses("rare"));
    }

    @Test
    void aRuleNothingSatisfiesIsIgnored() {
        FairRoll<Entry> roll = FairRoll.of(List.of(new Entry("a", 1, 0)), Entry::weight, Entry::id, Entry::tier)
                .rules(List.of(new FairRoll.Rule("legendary", 4, 1)));
        FairRoll.Luck luck = new FairRoll.Luck(List.of(), java.util.Map.of("legendary", 3));
        FairRoll.Result<Entry> result = roll.roll(luck, new SplittableRandom(2));
        assertEquals("a", result.picked().id());
        assertNull(result.forced());
        assertEquals(4, result.luck().misses("legendary"));
    }

    @Test
    void distinctRollsHonourPityFirst() {
        FairRoll<Entry> roll = roll().rules(List.of(new FairRoll.Rule("epic", 2, 1)));
        FairRoll.Luck luck = new FairRoll.Luck(List.of(), java.util.Map.of("epic", 1));
        List<Entry> offered = roll.rollDistinct(luck, 3, new SplittableRandom(5));
        assertEquals(3, offered.size());
        assertEquals("epic", offered.get(0).id());
        assertEquals(3, new HashSet<>(offered.stream().map(Entry::id).toList()).size());
    }

    @Test
    void distinctRollsStopWhenTheListRunsOut() {
        FairRoll<Entry> roll = FairRoll.of(List.of(new Entry("a", 1, 0), new Entry("b", 1, 0)),
                Entry::weight, Entry::id, Entry::tier);
        assertEquals(2, roll.rollDistinct(FairRoll.Luck.NONE, 5, new SplittableRandom(1)).size());
    }
}
