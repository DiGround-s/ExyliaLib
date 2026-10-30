package net.exylia.lib.replay.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the behind and orbit cameras sit, and that they look at the body.
 */
class CameraMathTest {

    private static final double CLOSE = 1e-9;

    @Test
    @DisplayName("behind a body facing south sits to its north, above its eyes, looking south and down")
    void behindFacingSouth() {
        double[] at = CameraMath.behind(10, 64, 20, 0);
        assertEquals(10, at[0], CLOSE);
        assertEquals(20 - CameraMath.BEHIND_DISTANCE, at[2], CLOSE);
        assertEquals(64 + CameraMath.EYE + CameraMath.BEHIND_HEIGHT, at[1], CLOSE);
        assertEquals(0, at[3], CLOSE);
        assertTrue(at[4] > 0, "looks down at the eyes");
        assertEquals(Math.toDegrees(Math.atan2(CameraMath.BEHIND_HEIGHT, CameraMath.BEHIND_DISTANCE)), at[4], CLOSE);
    }

    @Test
    @DisplayName("behind a body facing west sits to its east, looking west")
    void behindFacingWest() {
        double[] at = CameraMath.behind(0, 0, 0, 90);
        assertEquals(CameraMath.BEHIND_DISTANCE, at[0], CLOSE);
        assertEquals(0, at[2], CLOSE);
        assertEquals(90, at[3], CLOSE);
    }

    @Test
    @DisplayName("the orbit keeps its radius and height all the way round, always facing the body")
    void orbitCircles() {
        for (int angle = 0; angle < 360; angle += 15) {
            double[] at = CameraMath.orbit(5, 70, -3, angle);
            assertEquals(CameraMath.ORBIT_RADIUS, Math.hypot(at[0] - 5, at[2] + 3), CLOSE);
            assertEquals(70 + CameraMath.EYE + CameraMath.ORBIT_HEIGHT, at[1], CLOSE);
            // Facing the body: one step along the look lands nearer to it.
            double yaw = Math.toRadians(at[3]);
            double nearer = Math.hypot(at[0] - Math.sin(yaw) - 5, at[2] + Math.cos(yaw) + 3);
            assertEquals(CameraMath.ORBIT_RADIUS - 1, nearer, 1e-6);
        }
    }

    @Test
    @DisplayName("an orbit at zero degrees sits east of the body, looking west")
    void orbitStart() {
        double[] at = CameraMath.orbit(0, 0, 0, 0);
        assertEquals(CameraMath.ORBIT_RADIUS, at[0], CLOSE);
        assertEquals(0, at[2], CLOSE);
        assertEquals(90, at[3], CLOSE);
    }

    @Test
    @DisplayName("easing turns the short way round, across the seam at 180")
    void easeShortWay() {
        assertEquals(175 + 2.5, CameraMath.ease(175, -175, 0.25), CLOSE);
        assertEquals(-170 - 5, CameraMath.ease(-170, 170, 0.25), CLOSE);
        assertEquals(50, CameraMath.ease(0, 100, 0.5), CLOSE);
    }
}
