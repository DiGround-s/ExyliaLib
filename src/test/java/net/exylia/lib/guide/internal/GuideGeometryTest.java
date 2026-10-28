package net.exylia.lib.guide.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where guidance is placed, without a world. */
class GuideGeometryTest {

    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("a far target's stand-in stands the clamped distance towards it, at eye height")
    void marker() {
        double[] at = GuideGeometry.marker(0, 70, 0, 300, 20, 400, 30);
        assertEquals(18, at[0], EPSILON);
        assertEquals(70, at[1], EPSILON);
        assertEquals(24, at[2], EPSILON);
    }

    @Test
    @DisplayName("a target nearer than the clamp gets the target itself")
    void markerNear() {
        double[] at = GuideGeometry.marker(0, 70, 0, 10, 64, 0, 30);
        assertEquals(10, at[0], EPSILON);
        assertEquals(64, at[1], EPSILON);
        assertEquals(0, at[2], EPSILON);
    }

    @Test
    @DisplayName("a companion floats ahead along the view and a little below it")
    void ahead() {
        // Facing south (+z), level.
        double[] at = GuideGeometry.ahead(0, 70, 0, 0, 0, 2.5, 0.35);
        assertEquals(0, at[0], EPSILON);
        assertEquals(69.65, at[1], EPSILON);
        assertEquals(2.5, at[2], EPSILON);
        // Facing west (yaw 90 is -x).
        double[] west = GuideGeometry.ahead(0, 70, 0, 90, 0, 2, 0);
        assertEquals(-2, west[0], EPSILON);
    }

    @Test
    @DisplayName("a companion stops following the view at the pitch limits")
    void aheadPitchClamp() {
        double[] down = GuideGeometry.ahead(0, 70, 0, 0, 90, 2, 0);
        double[] limit = GuideGeometry.ahead(0, 70, 0, 0, GuideGeometry.COMPANION_PITCH_DOWN, 2, 0);
        assertEquals(limit[1], down[1], EPSILON);
        assertEquals(limit[2], down[2], EPSILON);
    }

    @Test
    @DisplayName("an anchored text keeps its size up close and grows in proportion after, up to the limit")
    void scale() {
        assertEquals(1f, GuideGeometry.scaleFor(3, 1f, 4f), 1e-6);
        assertEquals(2f, GuideGeometry.scaleFor(16, 1f, 4f), 1e-6);
        assertEquals(4f, GuideGeometry.scaleFor(500, 1f, 4f), 1e-6);
    }

    @Test
    @DisplayName("the automatic cut-over sits inside the view distance, never under 32 blocks")
    void farDistance() {
        assertEquals(136, GuideGeometry.farDistance(10), EPSILON);
        assertEquals(32, GuideGeometry.farDistance(2), EPSILON);
    }

    @Test
    @DisplayName("turns are measured the short way round")
    void turn() {
        assertEquals(20, GuideGeometry.turn(350, 10), EPSILON);
        assertEquals(90, GuideGeometry.turn(-90, 0), EPSILON);
        assertEquals(-90f, GuideGeometry.yawTowards(0, 0, 5, 0), 1e-4);
    }

    @Test
    @DisplayName("a teleport past half the view distance, never under 32 blocks, redraws the guidance")
    void longTeleport() {
        assertFalse(GuideGeometry.isLongTeleport(0, 0, 30, 0, 2));
        assertTrue(GuideGeometry.isLongTeleport(0, 0, 33, 0, 2));
        assertFalse(GuideGeometry.isLongTeleport(0, 0, 50, 50, 10));
        assertTrue(GuideGeometry.isLongTeleport(0, 0, 81, 0, 10));
    }
}
