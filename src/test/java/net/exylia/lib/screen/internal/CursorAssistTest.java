package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorAssistTest {

    private static final Layout.Placed A = box("a", 100, 100, 40, 16);
    /** Two pixels under {@link #A}, as the rows of a dense list are. */
    private static final Layout.Placed B = box("b", 100, 118, 40, 16);

    private static Layout.Placed box(String key, double x, double y, double w, double h) {
        return new Layout.Placed(null, key, x, y, w, h, 0);
    }

    @Test
    void frictionSlowsASlowMoveAndLeavesAFastOneAlone() {
        double[] slow = CursorAssist.move(110, 105, 2, 0, A, List.of());
        assertTrue(slow[0] < 2 && slow[0] > 2 * CursorAssist.FRICTION - 1e-9, "scaled a little: " + slow[0]);
        assertArrayEquals(new double[]{30, 0}, CursorAssist.move(110, 105, 30, 0, A, List.of()), 1e-9);
    }

    @Test
    void theMagnetBendsASlowMoveTowardABoxAndDoesNothingWhenFast() {
        double[] slow = CursorAssist.move(120, 90, 0, 3, null, List.of(A));
        assertTrue(slow[1] > 3, "bends toward the box below: " + slow[1]);
        assertTrue(slow[1] < 3 * (1 + CursorAssist.MAGNET), "only a part of the move, never a jump: " + slow[1]);
        assertArrayEquals(new double[]{0, 7}, CursorAssist.move(120, 90, 0, 7, null, List.of(A)), 1e-9);
    }

    @Test
    void movingAwayIsNeverResisted() {
        assertArrayEquals(new double[]{0, -3}, CursorAssist.move(120, 90, 0, -3, null, List.of(A)), 1e-9);
        assertArrayEquals(new double[]{3, 0}, CursorAssist.move(120, 90, 3, 0, null, List.of(A)), 1e-9);
    }

    /** The slowest hand there is, straight from the middle of a box to past its neighbour: it never turns back. */
    @Test
    void pullingAwayEscapesEvenBetweenTwoCloseBoxes() {
        double y = 108;
        for (int moves = 0; y < 170; moves++) {
            assertTrue(moves < 500, "trapped at y " + y);
            Layout.Placed over = A.holds(120, y, 3) ? A : B.holds(120, y, 3) ? B : null;
            double step = CursorAssist.move(120, y, 0, 0.5, over, List.of(A, B))[1];
            assertTrue(step > 0, "turned back at y " + y);
            y += step;
        }
        // In the gap, heading up and away from the closer box under it: only the one it heads for pulls.
        double[] up = CursorAssist.move(120, 117.5, 0, -1, null, List.of(A, B));
        assertTrue(up[1] < -1, "bends only toward where it heads: " + up[1]);
    }

    @Test
    void theToleranceIsSmallAndExpires() {
        assertTrue(CursorAssist.forgives(A, 145, 108, 100));
        assertFalse(CursorAssist.forgives(A, 145, 108, CursorAssist.TOLERANCE_MILLIS + 1));
        assertFalse(CursorAssist.forgives(A, 140 + CursorAssist.TOLERANCE_PIXELS + 1, 108, 100));
        assertEquals(0.7, CursorAssist.friction(0), 1e-9);
    }

    /** A hand moving at {@code perTick} pixels a tick for a second, sampled {@code hz} times: where the pointer ends. */
    private static double[] slide(int hz, double perTick, double x, double y, double dirX, double dirY, boolean over) {
        double millis = 1000.0 / hz, step = perTick * millis / CursorAssist.TICK_MILLIS;
        for (int i = 0; i < hz; i++) {
            Layout.Placed lit = over && A.holds(x, y) ? A : null;
            double[] move = CursorAssist.move(x, y, dirX * step, dirY * step, lit, lit == null ? List.of(A) : List.of(), millis);
            x += move[0];
            y += move[1];
        }
        return new double[]{x, y};
    }

    @Test
    void frictionIsTheSameAtAnyRate() {
        // Across the box, slowly: every move keeps the same share of itself whether it came a tick or a frame apart.
        double[] tick = CursorAssist.move(110, 105, 2, 0, A, List.of(), 50);
        double[] frame = CursorAssist.move(110, 105, 2 / 12.5, 0, A, List.of(), 4);
        assertEquals(tick[0], frame[0] * 12.5, 1e-9);
    }

    @Test
    void theMagnetBendsAsMuchAtAnyRate() {
        // Slowly past the top of the box, heading along it: the pull bends the path down toward it.
        double[] tick = slide(20, 1, 90, 92, 1, 0.1, false);
        double[] frame = slide(250, 1, 90, 92, 1, 0.1, false);
        assertTrue(tick[1] > 92 + 2, "the magnet bent it: " + tick[1]);
        assertEquals(tick[0], frame[0], 1.0);
        assertEquals(tick[1], frame[1], 1.0);
    }

    @Test
    void aMoveATickLongIsHelpedAsBefore() {
        assertArrayEquals(CursorAssist.move(120, 90, 0, 3, null, List.of(A)),
                CursorAssist.move(120, 90, 0, 3, null, List.of(A), CursorAssist.TICK_MILLIS), 1e-12);
    }
}
