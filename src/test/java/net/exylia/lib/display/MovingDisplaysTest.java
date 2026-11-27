package net.exylia.lib.display;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A ride lasts its length over its speed, corner to corner; the stride keeps a flow under the cap. */
class MovingDisplaysTest {

    @Test
    void rideLastsItsLengthOverItsSpeedAndEndsAtTheLastPoint() {
        DisplayMotion ride = MovingDisplays.along(List.of(new Vector(0, 0, 0), new Vector(4, 0, 0), new Vector(4, 0, 6)), 2.0);
        assertEquals(5000, ride.lifeMillis());
        DisplayKeyframe last = ride.poses().get(ride.poses().size() - 1);
        assertEquals(4f, last.x(), 1e-6);
        assertEquals(6f, last.z(), 1e-6);
        assertEquals(3, ride.poses().size(), "one pose per corner, linear legs");
    }

    @Test
    void rideNeedsTwoPointsAndASpeed() {
        assertThrows(IllegalArgumentException.class, () -> MovingDisplays.along(List.of(new Vector()), 1));
        assertThrows(IllegalArgumentException.class, () -> MovingDisplays.along(List.of(new Vector(), new Vector(1, 0, 0)), 0));
    }

    @Test
    void strideKeepsAFlowUnderTheCap() {
        assertEquals(1, MovingDisplays.stride(2, 8000, 40), "16 on the way: draw all");
        assertEquals(2, MovingDisplays.stride(10, 8000, 40), "80 on the way: every second");
        assertEquals(6, MovingDisplays.stride(10, 8000, 15));
        assertEquals(1, MovingDisplays.stride(0, 8000, 40));
    }
}
