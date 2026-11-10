package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How close a screen stands, and how the stage behind its window is kept clear and floored. */
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
    void aLevelFloorsNearEdgeNeverRisesOverTheFeet() {
        for (double ahead = 2.5; ahead <= 12; ahead += 0.5) {
            for (double over = -3; over <= 3; over += 0.05) {
                double front = PreviewStage.floorFront(ahead, over);
                // Its top near edge, seen from the eyes, where it crosses the feet's depth.
                double nearEdge = over * ahead / (ahead - front);
                assertTrue(nearEdge <= over + PreviewStage.FLOOR_COVER + 1e-9, "ahead " + ahead + " over " + over);
                if (over <= 0) assertEquals(PreviewStage.FLOOR_BACK, front, 1e-9, "seen from above, all of it");
                assertTrue(front > 0);
            }
        }
    }

    @Test
    void everyBlockAWindowShowsIsCleared() {
        // Rays through every part of the window, out to the wall: each block one passes is in the view.
        java.util.Random random = new java.util.Random(7);
        for (int run = 0; run < 400; run++) {
            float yaw = random.nextFloat() * 360 - 180;
            double cx = random.nextDouble() * 1.8 - 0.9, cy = random.nextDouble() - 0.5;
            double hw = 0.1 + random.nextDouble() * 0.5, hh = 0.1 + random.nextDouble() * 0.4;
            double far = 3 + random.nextDouble() * 12;
            double ex = random.nextDouble(), ey = random.nextDouble(), ez = random.nextDouble();
            double[][] view = PreviewStage.view(yaw, PreviewStage.PITCH, cx, cy, hw, hh, far);
            double a = Math.toRadians(yaw);
            double[] f = {-Math.sin(a), 0, Math.cos(a)}, r = {-Math.cos(a), 0, -Math.sin(a)};
            for (double u = -1; u <= 1; u += 0.125) {
                for (double v = -1; v <= 1; v += 0.125) {
                    double across = cx + u * hw, up = cy + v * hh;
                    for (double t = LiveScreen.NEAR; t <= far; t += 0.01) {
                        double px = ex + t * (f[0] + across * r[0]), py = ey + t * up, pz = ez + t * (f[2] + across * r[2]);
                        double bx = Math.floor(px) + 0.5, by = Math.floor(py) + 0.5, bz = Math.floor(pz) + 0.5;
                        assertTrue(PreviewStage.seen(view, bx - ex, by - ey, bz - ez),
                                "yaw " + yaw + " window " + cx + "," + cy + " " + hw + "x" + hh + " missed a block " + t + " ahead");
                    }
                }
            }
        }
    }
}
