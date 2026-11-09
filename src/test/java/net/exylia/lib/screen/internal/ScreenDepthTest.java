package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How close a screen stands, and why the floor in its window is tipped rather than the camera. */
class ScreenDepthTest {

    /** How far from the eyes a corner of the view is, at the depth a screen stands at, for a 16:9 view of this field of view. */
    private static double corner(double fov) {
        double up = Math.tan(Math.toRadians(fov / 2));
        double across = up * 16 / 9;
        return LiveScreen.DEPTH * Math.sqrt(1 + up * up + across * across);
    }

    @Test
    void everyCornerOfTheViewStandsCloserThanAWallCanBe() {
        assertTrue(corner(110) < 0.3, "eyes are 0.3 from a wall: " + corner(110));
        assertTrue(corner(70) < 0.18, "and 0.18 under a ceiling: " + corner(70));
    }

    @Test
    void theCursorStaysPastTheNearPlane() {
        assertTrue(LiveScreen.DEPTH - LiveScreen.CURSOR_LAYER * LiveScreen.LAYER > LiveScreen.NEAR);
    }

    @Test
    void aLevelCameraLeansNothingAndATippedOneDoes() {
        // Across the view and tan(pitch) from an upright's vanishing point: a window at the top right under the old pitch.
        assertEquals(0, lean(0.93, PreviewStage.PITCH), 1e-9);
        assertTrue(lean(0.93, 26) > 20);
    }

    private static double lean(double across, double pitch) {
        return Math.toDegrees(Math.atan(across * Math.tan(Math.toRadians(pitch))));
    }

    @Test
    void aFloorSeenFromBelowTipsAndOneSeenFromAboveDoesNot() {
        assertEquals(24, PreviewStage.floorTilt(8, 16), 1e-9);
        assertEquals(0, PreviewStage.floorTilt(-20, 16), 1e-9);
    }
}
