package net.exylia.lib.clientmob.internal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

class MobMathTest {

    @Test
    void yawFollowsMinecraftConvention() {
        assertEquals(0f, MobMath.yaw(0, 1), 1e-4);      // +Z
        assertEquals(90f, Math.abs(MobMath.yaw(-1, 0)), 1e-4); // -X is 90
        assertEquals(-90f, MobMath.yaw(1, 0), 1e-4);    // +X
        assertEquals(-180f, MobMath.yaw(0, -1), 1e-4);  // -Z wraps to -180
    }

    @Test
    void pitchLooksUpNegative() {
        assertEquals(-45f, MobMath.pitch(0, 1, 1), 1e-4);
        assertEquals(45f, MobMath.pitch(1, -1, 0), 1e-4);
        assertEquals(0f, MobMath.pitch(3, 0, 4), 1e-4);
    }

    @Test
    void wrapStaysInHalfTurn() {
        assertEquals(-170, MobMath.wrap(190), 1e-9);
        assertEquals(170, MobMath.wrap(-190), 1e-9);
        assertEquals(-180, MobMath.wrap(180), 1e-9);
        assertEquals(10, MobMath.wrap(730), 1e-9);
    }

    @Test
    void stepWalksStraightAndArrivesExactly() {
        MobMath.Step s = MobMath.step(0, 0, 0, 3, 0, 4, 1);
        assertFalse(s.arrived());
        assertEquals(0.6, s.x(), 1e-9);
        assertEquals(0.8, s.z(), 1e-9);
        MobMath.Step last = MobMath.step(2.5, 0, 3.5, 3, 0, 4, 1);
        assertTrue(last.arrived());
        assertEquals(3, last.x());
        assertEquals(4, last.z());
        assertTrue(MobMath.step(1, 1, 1, 1, 1, 1, 0).arrived());
    }

    @Test
    void walkTakesDistanceOverSpeedTicks() {
        double x = 0;
        int steps = 0;
        while (true) {
            MobMath.Step s = MobMath.step(x, 0, 0, 10, 0, 0, 2.0 * 0.1);
            steps++;
            x = s.x();
            if (s.arrived()) break;
        }
        assertEquals(50, steps, 1); // 10 blocks at 2 b/s, 10 steps a second
    }

    @Test
    void idsNeverRepeat() {
        IntSupplier pool = IdPool.counter();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int id = pool.getAsInt();
            assertTrue(id > IdPool.START);
            assertTrue(seen.add(id));
        }
    }
}
