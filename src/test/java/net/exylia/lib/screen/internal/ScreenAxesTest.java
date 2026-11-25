package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** A held camera's right, which a polled arrow moves along: the camera's right hand as it faces each way. */
class ScreenAxesTest {

    @Test
    void facingSouthRightIsWest() {
        assertArrayEquals(new double[]{-1, 0}, ScreenMath.right(0), 1e-9);
    }

    @Test
    void facingWestRightIsNorth() {
        assertArrayEquals(new double[]{0, -1}, ScreenMath.right(90), 1e-9);
    }

    @Test
    void facingNorthRightIsEast() {
        assertArrayEquals(new double[]{1, 0}, ScreenMath.right(180), 1e-9);
    }

    @Test
    void itIsAlwaysAcrossTheView() {
        for (float yaw = -360; yaw <= 360; yaw += 7.5f) {
            double a = Math.toRadians(yaw);
            double[] right = ScreenMath.right(yaw);
            // Forward is (-sin, cos): right is level and square to it.
            org.junit.jupiter.api.Assertions.assertEquals(0, right[0] * -Math.sin(a) + right[1] * Math.cos(a), 1e-9);
        }
    }
}
