package net.exylia.lib.region;

import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegionModelTest {

    @Test
    @DisplayName("Region identifiers normalize, validate, and sort by stable representation")
    void regionIdsNormalizeValidateAndSort() {
        RegionId normalized = RegionId.parse("  Exylia : Spawn-1.Test ");
        assertEquals("exylia", normalized.namespace());
        assertEquals("spawn-1.test", normalized.value());
        assertEquals("exylia:spawn-1.test", normalized.toString());

        List<RegionId> ids = new ArrayList<>(List.of(
                RegionId.parse("z:a"), RegionId.parse("a:z"), RegionId.parse("a:a")));
        ids.sort(null);
        assertEquals(List.of(RegionId.parse("a:a"), RegionId.parse("a:z"), RegionId.parse("z:a")), ids);

        for (String malformed : List.of("missing", ":value", "namespace:", "a:b:c", "a:has space", "a:á")) {
            assertThrows(IllegalArgumentException.class, () -> RegionId.parse(malformed), malformed);
        }
        assertThrows(NullPointerException.class, () -> RegionId.parse(null));
    }

    @Test
    @DisplayName("World identity uses UUID equality and does not retain Bukkit worlds")
    void worldIdentityIsPortable() {
        UUID id = UUID.randomUUID();
        World first = world(id, "first-name");
        World second = world(id, "first-name");
        assertNotSame(first, second);
        assertEquals(WorldIdentity.from(first), WorldIdentity.from(second));

        WeakReference<World> reference = captureAndReleaseWorld(id);
        awaitCollection(reference);
        assertNull(reference.get(), "WorldIdentity must not hold a Bukkit World reference");
    }

    @Test
    @DisplayName("Cuboids normalize coordinates and use half-open boundaries")
    void cuboidNormalizationAndHalfOpenContainment() {
        Cuboid cuboid = new Cuboid(5, 7, 9, -1, -2, -3);
        assertEquals(new Cuboid(-1, -2, -3, 5, 7, 9), cuboid);
        assertTrue(cuboid.contains(-1, -2, -3));
        assertTrue(cuboid.contains(Math.nextDown(5.0), Math.nextDown(7.0), Math.nextDown(9.0)));
        assertFalse(cuboid.contains(5, 0, 0));
        assertFalse(cuboid.contains(0, 7, 0));
        assertFalse(cuboid.contains(0, 0, 9));
    }

    @Test
    @DisplayName("Block cuboids preserve inclusive Commons corners in every direction")
    void blockCuboidsPreserveInclusiveCorners() {
        Cuboid blocks = Cuboid.blocks(-2, 4, -5, -4, 2, -3);
        assertEquals(new Cuboid(-4, 2, -5, -1, 5, -2), blocks);
        assertTrue(blocks.contains(-4, 2, -5));
        assertTrue(blocks.contains(-2.0001, 4.9999, -3.0001));
        assertFalse(blocks.contains(-1, 4, -3));

        Cuboid maximum = Cuboid.block(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        double exclusive = (double) Integer.MAX_VALUE + 1.0;
        assertEquals(exclusive, maximum.maxX());
        assertTrue(maximum.contains(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(maximum.contains(exclusive, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("Unbounded horizontal shapes ignore Y and curved boundaries are exact")
    void shapeSemantics() {
        UnboundedYRectangle rectangle = new UnboundedYRectangle(16, 16, -16, -16);
        assertTrue(rectangle.contains(0, Double.MAX_VALUE, 0));
        assertTrue(rectangle.contains(0, -Double.MAX_VALUE, 0));
        assertFalse(rectangle.contains(16, 0, 0));

        Sphere sphere = new Sphere(1, 2, 3, 5);
        assertTrue(sphere.contains(4, 6, 3), "3-4-5 boundary is included");
        assertFalse(sphere.contains(Math.nextUp(6.0), 2, 3));

        HorizontalCylinder cylinder = new HorizontalCylinder(-2, 4, 5);
        assertTrue(cylinder.contains(1, Double.MAX_VALUE, 8));
        assertFalse(cylinder.contains(3.000_001, -Double.MAX_VALUE, 4));
    }

    @Test
    @DisplayName("Shapes reject non-finite values, non-positive radii, and empty bounds")
    void invalidShapesAreRejected() {
        for (double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new Cuboid(invalid, 0, 0, 1, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new UnboundedYRectangle(0, 0, invalid, 1));
            assertThrows(IllegalArgumentException.class, () -> new Sphere(0, 0, 0, invalid));
            assertThrows(IllegalArgumentException.class, () -> new HorizontalCylinder(0, 0, invalid));
        }
        for (double radius : List.of(0.0, -1.0)) {
            assertThrows(IllegalArgumentException.class, () -> new Sphere(0, 0, 0, radius));
            assertThrows(IllegalArgumentException.class, () -> new HorizontalCylinder(0, 0, radius));
        }
        assertThrows(IllegalArgumentException.class, () -> new Cuboid(0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new UnboundedYRectangle(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new HorizontalBounds(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new VerticalBounds(1, 1));
    }

    private static WeakReference<World> captureAndReleaseWorld(UUID id) {
        World world = world(id, "temporary");
        WorldIdentity identity = WorldIdentity.from(world);
        assertEquals(id, identity.id());
        return new WeakReference<>(world);
    }

    private static void awaitCollection(WeakReference<?> reference) {
        for (int attempt = 0; attempt < 40 && reference.get() != null; attempt++) {
            System.gc();
            byte[] pressure = new byte[64 * 1024];
            assertEquals(64 * 1024, pressure.length);
            Thread.yield();
        }
    }

    private static World world(UUID id, String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "getName" -> name;
                    case "toString" -> "World[" + name + ']';
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
