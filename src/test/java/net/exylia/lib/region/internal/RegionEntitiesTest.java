package net.exylia.lib.region.internal;

import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.Sphere;
import net.exylia.lib.region.UnboundedYRectangle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of clearing a region that do not need a server: which box to
 * sweep, and what counts as loose.
 */
class RegionEntitiesTest {

    private static Entity fake(Class<? extends Entity> type) {
        return (Entity) Proxy.newProxyInstance(
                RegionEntitiesTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Fake[" + type.getSimpleName() + "]";
                    default -> null;
                });
    }

    @Test
    @DisplayName("the box covers the last block, not up to its corner")
    void boxIsInclusive() {
        // 0..15 is sixteen blocks, and an item resting on the block at 15
        // stands somewhere in [15, 16). A box ending at 15.0 misses it.
        BoundingBox box = RegionEntities.box(Cuboid.blocks(0, 64, 0, 15, 79, 15), -64, 320);

        assertEquals(0.0, box.getMinX());
        assertEquals(16.0, box.getMaxX());
        assertEquals(64.0, box.getMinY());
        assertEquals(80.0, box.getMaxY());
        assertEquals(16.0, box.getMaxZ());
    }

    @Test
    @DisplayName("a shape without vertical bounds takes the world's")
    void unboundedTakesTheWorldHeight() {
        BoundingBox box = RegionEntities.box(
                new UnboundedYRectangle(0.0, 0.0, 10.0, 10.0), -64, 320);

        assertEquals(-64.0, box.getMinY());
        assertEquals(320.0, box.getMaxY());
    }

    @Test
    @DisplayName("a sphere's box encloses it")
    void sphereIsEnclosed() {
        BoundingBox box = RegionEntities.box(new Sphere(0.0, 64.0, 0.0, 8.0), -64, 320);

        assertTrue(box.getMinX() <= -8.0, "reaches the west edge");
        assertTrue(box.getMaxX() >= 8.0, "reaches the east edge");
        // The box is only a narrowing: membership stays the shape's.
        assertFalse(new Sphere(0.0, 64.0, 0.0, 8.0).contains(7.0, 71.0, 7.0),
                "a corner of the box is outside the sphere");
    }

    @Test
    @DisplayName("loose is what a round dropped, never what somebody placed")
    void looseIsDroppings() {
        assertTrue(RegionEntities.loose(fake(Item.class)), "a dropped item");
        assertTrue(RegionEntities.loose(fake(ExperienceOrb.class)), "an orb");
        assertTrue(RegionEntities.loose(fake(Arrow.class)), "a projectile");

        assertFalse(RegionEntities.loose(fake(ArmorStand.class)), "a decoration");
        assertFalse(RegionEntities.loose(fake(Zombie.class)), "a mob");
    }
}
