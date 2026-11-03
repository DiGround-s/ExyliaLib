package net.exylia.lib.spatial.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelStepsTest {

    /** Plays a wheel the way a menu does: the first step at once, then one a beat until it answers 0. */
    private static List<Integer> playOut(WheelSteps steps) {
        List<Integer> played = new ArrayList<>();
        for (int step = steps.next(); step != 0; step = steps.next()) played.add(step);
        return played;
    }

    @Test
    void oneNotchIsOneItemAtOnce() {
        WheelSteps steps = new WheelSteps(12);
        assertTrue(steps.add(1));
        assertEquals(1, steps.next());
        assertEquals(2L, steps.beat(), "a lone step waits the longer beat");
        assertEquals(0, steps.next());
        assertFalse(steps.playing());
    }

    @Test
    void aFlickOfFivePlaysFiveStepsInTurn() {
        WheelSteps steps = new WheelSteps(12);
        assertTrue(steps.add(1));
        assertEquals(1, steps.next());
        // The rest of the flick lands while the first step plays: queued, not started again.
        for (int i = 0; i < 4; i++) assertFalse(steps.add(1));
        assertEquals(1L, steps.beat(), "a waiting flick plays quickly");
        assertEquals(List.of(1, 1, 1, 1), playOut(steps));
    }

    @Test
    void theOtherWayCancelsWhatWaits() {
        WheelSteps steps = new WheelSteps(12);
        steps.add(1);
        steps.next();
        steps.add(3);
        steps.add(-2);
        assertEquals(List.of(1), playOut(steps));
        assertTrue(steps.add(-1), "once it stopped, the next notch starts it again");
        assertEquals(-1, steps.next());
    }

    @Test
    void aLongFlickKeepsAtMostTheCap() {
        WheelSteps steps = new WheelSteps(4);
        assertTrue(steps.add(30));
        assertEquals(4, playOut(steps).size());
        assertTrue(steps.add(-30));
        assertEquals(List.of(-1, -1, -1, -1), playOut(steps));
    }

    @Test
    void aClearedQueueEndsOnTheNextBeat() {
        WheelSteps steps = new WheelSteps(12);
        steps.add(5);
        steps.next();
        steps.clear();
        assertFalse(steps.add(0));
        assertEquals(0, steps.next());
        assertFalse(steps.playing());
    }

    @Test
    void stepsMoveTheFirstItemRoundTheRing() {
        List<String> all = List.of("a", "b", "c", "d", "e");
        int first = 0;
        WheelSteps steps = new WheelSteps(12);
        steps.add(-1);
        first = Geometry.wrapItem(first + steps.next(), all.size(), 3);
        assertEquals(4, first, "back from the first wraps to the last");
        assertEquals(List.of("e", "a", "b"), Geometry.ring(all, first, 3));
        assertEquals(1, Geometry.wrapItem(first + 2, all.size(), 3));
    }
}
