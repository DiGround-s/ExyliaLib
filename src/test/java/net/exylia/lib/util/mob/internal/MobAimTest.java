package net.exylia.lib.util.mob.internal;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobAimTest {

    private static final Vector ORIGIN = new Vector(0, 64, 0);

    @Test
    @DisplayName("yaw follows Minecraft: 0 faces +z, 90 faces -x")
    void yaw() {
        assertEquals(0, MobAim.yaw(ORIGIN, new Vector(0, 64, 5), 33), 1.0E-4);
        assertEquals(90, MobAim.yaw(ORIGIN, new Vector(-5, 64, 0), 33), 1.0E-4);
        assertEquals(33, MobAim.yaw(ORIGIN, new Vector(0, 70, 0), 33), 1.0E-4, "straight above keeps the facing");
        Vector facing = MobAim.facing(90);
        assertEquals(-1, facing.getX(), 1.0E-9);
        assertEquals(0, facing.getZ(), 1.0E-9);
    }

    @Test
    @DisplayName("a cone holds what is ahead, within its reach and half its angle each side")
    void cone() {
        // Facing +z, 60 degrees: 30 each side.
        assertTrue(MobAim.inCone(ORIGIN, 0, 8, 60, new Vector(0, 64, 5)));
        assertTrue(MobAim.inCone(ORIGIN, 0, 8, 60, new Vector(2.5, 64, 5)), "26.6 degrees off");
        assertFalse(MobAim.inCone(ORIGIN, 0, 8, 60, new Vector(3.5, 64, 5)), "35 degrees off");
        assertFalse(MobAim.inCone(ORIGIN, 0, 8, 60, new Vector(0, 64, -5)), "behind");
        assertFalse(MobAim.inCone(ORIGIN, 0, 8, 60, new Vector(0, 64, 9)), "past its reach");
        assertFalse(MobAim.inCone(ORIGIN, 0, 8, 60, new Vector(0, 70, 5)), "far above");
        assertTrue(MobAim.inCone(ORIGIN, 0, 8, 60, ORIGIN.clone()), "on the tip");
        assertTrue(MobAim.inCone(ORIGIN, 90, 8, 60, new Vector(-5, 64, 1)), "turned towards -x");
        assertTrue(MobAim.inCone(ORIGIN, 0, 8, 360, new Vector(0, 64, -5)), "a full turn holds everything in reach");
    }

    @Test
    @DisplayName("a line holds what is within half its width of the segment, and nothing past its ends")
    void line() {
        Vector from = ORIGIN;
        Vector to = new Vector(0, 64, 10);

        assertTrue(MobAim.onLine(from, to, 1.6, new Vector(0.7, 64, 5)));
        assertFalse(MobAim.onLine(from, to, 1.6, new Vector(0.9, 64, 5)));
        assertTrue(MobAim.onLine(from, to, 1.6, new Vector(0, 64, 10.5)), "the end rounds off");
        assertFalse(MobAim.onLine(from, to, 1.6, new Vector(0, 64, 11)), "past the end");
        assertFalse(MobAim.onLine(from, to, 1.6, new Vector(0, 64, -2)), "behind the mob");
        assertFalse(MobAim.onLine(from, to, 1.6, new Vector(0, 68, 5)), "over it");
    }

    @Test
    @DisplayName("GROUND is where the target stood at the wind-up, not where it walks to, and never beyond 24 blocks")
    void groundSnapshot() {
        Location origin = new Location(null, 0, 64, 0, 45, 0);
        Location target = new Location(null, 3, 64, 4);

        MobAim.Lock lock = MobAim.lock(origin, target);
        target.add(10, 0, 10);
        origin.add(5, 0, 0);

        assertNotNull(lock.point());
        assertEquals(new Vector(3, 64, 4), lock.point().toVector(), "the target walking off does not move it");
        assertEquals(new Vector(0, 64, 0), lock.origin().toVector());
        assertEquals(MobAim.yaw(new Vector(0, 0, 0), new Vector(3, 0, 4), 0), lock.yaw(), 1.0E-4);

        MobAim.Lock far = MobAim.lock(new Location(null, 0, 64, 0), new Location(null, 0, 64, 100));
        assertEquals(24, far.point().toVector().distance(new Vector(0, 64, 0)), 1.0E-9);

        MobAim.Lock none = MobAim.lock(new Location(null, 0, 64, 0, 45, 0), null);
        assertEquals(45, none.yaw());
        assertEquals(null, none.point());
    }
}
