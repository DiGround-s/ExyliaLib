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

    @Test
    void theFloorsNearEdgeNeverRisesIntoTheBodysFeet() {
        // Seen from the eyes, the floor's top near edge must stay at or below the feet, wherever the window is.
        for (float yaw = -180; yaw < 180; yaw += 37) {
            double a = Math.toRadians(yaw);
            double fx = -Math.sin(a), fz = Math.cos(a);
            for (double elevation = -40; elevation <= 40; elevation += 5) {
                net.exylia.lib.display.Rotation turn = PreviewStage.floorTurn(yaw, elevation);
                float[] near = null;
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        float[] p = turn.apply(new float[]{(float) (sx * 1.2), (float) (PreviewStage.FLOOR_THICK / 2),
                                (float) (sz * PreviewStage.FLOOR_WIDE / 2)});
                        p[1] -= (float) (PreviewStage.FLOOR_THICK / 2);
                        if (near == null || p[0] * fx + p[2] * fz < near[0] * fx + near[2] * fz) near = p;
                    }
                }
                for (double ahead = 2.5; ahead <= 12; ahead += 4.75) {
                    double eyeY = -ahead * Math.tan(Math.toRadians(elevation));
                    double nearAhead = ahead + near[0] * fx + near[2] * fz;
                    double seen = Math.toDegrees(Math.atan2(near[1] - eyeY, nearAhead));
                    assertTrue(seen <= elevation + 1e-3, "yaw " + yaw + " elevation " + elevation + " ahead " + ahead
                            + ": near edge at " + seen + " degrees over feet at " + elevation);
                }
            }
        }
    }
}
