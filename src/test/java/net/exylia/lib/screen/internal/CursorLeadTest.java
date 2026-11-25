package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The prediction cursor over a trace recorded on a real client: {millis, x, y} per rotation packet, then a stop. */
class CursorLeadTest {

    private static final double[][] TRACE = {
            {4948, -2.09, 11.03}, {5000, 14.90, 13.41}, {5055, 35.46, 16.69}, {5097, 49.17, 19.07},
            {5148, 62.88, 22.05}, {5200, 70.03, 24.14}, {5251, 74.20, 26.22}, {5303, 73.01, 28.01},
            {5354, 65.86, 29.80}, {5405, 55.73, 30.99}, {5447, 43.51, 31.89},
    };
    private static final double BOUND = 10;

    @Test
    void itLagsLessThanTheRealPointerOverTheTrace() {
        CursorLead lead = new CursorLead();
        double behindNormal = 0, behindLead = 0;
        for (int i = 0; i < TRACE.length - 1; i++) {
            double[] at = TRACE[i];
            double[] drawn = lead.moved(at[1], at[2], (long) at[0], BOUND);
            // Where the mouse is by the time a packet's position is on screen: the next packet is about that far on.
            double[] then = TRACE[i + 1];
            behindNormal += Math.hypot(then[1] - at[1], then[2] - at[2]);
            behindLead += Math.hypot(then[1] - drawn[0], then[2] - drawn[1]);
        }
        assertTrue(behindLead < behindNormal, behindLead + " vs " + behindNormal);
    }

    @Test
    void itNeverDrawsFurtherThanTheBound() {
        CursorLead lead = new CursorLead();
        for (double[] at : TRACE) {
            double[] drawn = lead.moved(at[1], at[2], (long) at[0], BOUND);
            assertTrue(Math.hypot(drawn[0] - at[1], drawn[1] - at[2]) <= BOUND + 1e-9);
        }
        // A flick, well past the bound.
        lead.moved(0, 0, 10_000, BOUND);
        double[] drawn = lead.moved(200, 0, 10_050, BOUND);
        assertEquals(BOUND, drawn[0] - 200, 1e-9);
    }

    @Test
    void aReversalDoesNotFlingItTheOldWay() {
        CursorLead lead = new CursorLead();
        lead.moved(0, 0, 0, BOUND);
        lead.moved(50, 0, 50, BOUND);
        lead.moved(100, 0, 100, BOUND);
        double[] drawn = lead.moved(95, 0, 150, BOUND);
        assertTrue(drawn[0] <= 95, "still ahead on the way it no longer goes: " + drawn[0]);
    }

    @Test
    void onAStopItEndsExactlyOnThePointer() {
        CursorLead lead = new CursorLead();
        for (double[] at : TRACE) lead.moved(at[1], at[2], (long) at[0], BOUND);
        lead.rest();
        double[] end = TRACE[TRACE.length - 1];
        assertArrayEquals(new double[]{end[1], end[2]}, lead.moved(end[1], end[2], (long) end[0] + 60, BOUND));
    }

    @Test
    void aLongGapIsANewMovement() {
        CursorLead lead = new CursorLead();
        lead.moved(0, 0, 0, BOUND);
        lead.moved(50, 0, 50, BOUND);
        assertArrayEquals(new double[]{60, 0}, lead.moved(60, 0, 1_000, BOUND));
    }

    @Test
    void anUnknownModeIsNormal() {
        assertEquals(CursorLead.Mode.NORMAL, CursorLead.Mode.of("nonsense"));
        assertEquals(CursorLead.Mode.PREDICTION, CursorLead.Mode.of("prediction"));
        assertEquals(CursorLead.Mode.NORMAL, CursorLead.Mode.PREDICTION.next());
    }

    /** Where a timed lead draws a hand that speeds up then slows down, sampled {@code hz} times a second, at {@code atMs}. */
    private static double ledAt(int hz, double atMs) {
        CursorLead lead = new CursorLead();
        lead.timed(true);
        double step = 1000.0 / hz, drawn = 0;
        for (double t = 0; t <= atMs + 1e-9; t += step) {
            // Speeds up for 300 ms, then slows: x is the integral of a triangle of speed.
            double x = t < 300 ? t * t / 600 : 150 + (t - 300) - (t - 300) * (t - 300) / 600;
            drawn = lead.moved(x, 0, t, 1000)[0] - x;
        }
        return drawn;
    }

    @Test
    void aTimedLeadFollowsTheHandAtTheSamePaceAtAnyRate() {
        for (double at : new double[]{100, 250, 400}) {
            double tick = ledAt(20, at), frame = ledAt(250, at);
            assertEquals(tick, frame, Math.max(1.5, Math.abs(tick) * 0.2), "at " + at + " ms: " + tick + " vs " + frame);
        }
    }

    @Test
    void untimedEveryMoveDecidesTheSameShareAsATicksDid() {
        CursorLead untimed = new CursorLead();
        untimed.moved(0, 0, 0, 1000);
        // 1 pixel a millisecond, 40 ms after the last: six tenths of it, whatever the gap.
        assertEquals(40 + 0.6 * CursorLead.LEAD_MILLIS, untimed.moved(40, 0, 40, 1000)[0], 1e-9);
        CursorLead timed = new CursorLead();
        timed.timed(true);
        timed.moved(0, 0, 0, 1000);
        assertTrue(timed.moved(40, 0, 40, 1000)[0] < 40 + 0.6 * CursorLead.LEAD_MILLIS, "a shorter move decides less");
    }
}
