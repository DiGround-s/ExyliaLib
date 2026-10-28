package net.exylia.lib.ability.internal;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which blocks an area break plans, and how the budget slices them: pure
 * logic over a map of materials, no world.
 */
class AreaPlannerTest {

    private static final Predicate<Material> SOLID = type -> type != Material.AIR && type != Material.BEDROCK;

    /** Stone everywhere, unless placed otherwise. */
    private final Map<Long, Material> placed = new HashMap<>();
    private final AreaPlanner.TypeView world = (x, y, z) ->
            placed.getOrDefault(PackedBlocks.pack(x, y, z), Material.STONE);

    private PackedBlocks plan(AreaPlanner.Kind kind, int radius, int depth, int max, int lx, int ly, int lz) {
        PackedBlocks out = new PackedBlocks(8);
        AreaPlanner.plan(kind, radius, depth, max, 10, 64, -10, lx, ly, lz, world, SOLID, out);
        return out;
    }

    private static Set<Long> positions(PackedBlocks blocks) {
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) out.add(blocks.get(i));
        return out;
    }

    @Test
    @DisplayName("packing round-trips every coordinate a world holds, negatives included")
    void packing() {
        int[][] points = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 319, -29_999_999}, {-30_000_000, -2048, 2047}};
        for (int[] p : points) {
            long packed = PackedBlocks.pack(p[0], p[1], p[2]);
            assertEquals(p[0], PackedBlocks.x(packed));
            assertEquals(p[1], PackedBlocks.y(packed));
            assertEquals(p[2], PackedBlocks.z(packed));
        }
    }

    @Test
    @DisplayName("a cube of radius 1 is the 26 blocks around the origin, never the origin")
    void cube() {
        PackedBlocks cube = plan(AreaPlanner.Kind.CUBE, 1, 0, 4096, 0, 0, 1);
        assertEquals(26, cube.size());
        assertFalse(positions(cube).contains(PackedBlocks.pack(10, 64, -10)));
    }

    @Test
    @DisplayName("air, unbreakables and the blacklist are left out while planning")
    void filters() {
        placed.put(PackedBlocks.pack(11, 64, -10), Material.AIR);
        placed.put(PackedBlocks.pack(9, 64, -10), Material.BEDROCK);
        assertEquals(24, plan(AreaPlanner.Kind.CUBE, 1, 0, 4096, 0, 0, 1).size());
    }

    @Test
    @DisplayName("a plane faces the breaker: across the look axis, layers along it")
    void plane() {
        PackedBlocks wall = plan(AreaPlanner.Kind.PLANE, 1, 0, 4096, 0, 0, 1);
        assertEquals(8, wall.size());
        for (int i = 0; i < wall.size(); i++) assertEquals(-10, PackedBlocks.z(wall.get(i)), "one layer, at the origin's z");

        PackedBlocks floor = plan(AreaPlanner.Kind.PLANE, 1, 0, 4096, 0, -1, 0);
        for (int i = 0; i < floor.size(); i++) assertEquals(64, PackedBlocks.y(floor.get(i)), "looking down: flat");

        PackedBlocks side = plan(AreaPlanner.Kind.PLANE, 1, 0, 4096, 1, 0, 0);
        for (int i = 0; i < side.size(); i++) assertEquals(10, PackedBlocks.x(side.get(i)), "looking along x");

        PackedBlocks tunnel = plan(AreaPlanner.Kind.PLANE, 1, 2, 4096, 0, 0, 1);
        assertEquals(26, tunnel.size(), "3 layers of 9, minus the origin");
        assertTrue(positions(tunnel).contains(PackedBlocks.pack(10, 64, -8)), "layers go the way the breaker looks");
    }

    @Test
    @DisplayName("a vein follows the same material, diagonals included, and stops at its cap")
    void vein() {
        // A diagonal staircase of ore through air.
        Map<Long, Material> ore = new HashMap<>();
        for (int i = 0; i < 100; i++) ore.put(PackedBlocks.pack(10 + i, 64 + i, -10), Material.IRON_ORE);
        ore.put(PackedBlocks.pack(0, 0, 0), Material.IRON_ORE); // far away: not connected
        AreaPlanner.TypeView view = (x, y, z) -> ore.getOrDefault(PackedBlocks.pack(x, y, z), Material.AIR);

        PackedBlocks capped = new PackedBlocks(8);
        AreaPlanner.plan(AreaPlanner.Kind.VEIN, 0, 0, 64, 10, 64, -10, 0, 0, 1, view, SOLID, capped);
        assertEquals(64, capped.size());

        PackedBlocks whole = new PackedBlocks(8);
        AreaPlanner.plan(AreaPlanner.Kind.VEIN, 0, 0, 4096, 10, 64, -10, 0, 0, 1, view, SOLID, whole);
        assertEquals(99, whole.size(), "every connected block but the origin, and not the far one");
        assertFalse(positions(whole).contains(PackedBlocks.pack(0, 0, 0)));
    }

    @Test
    @DisplayName("the per-tick budget slices the plan, and the rest waits for the next tick")
    void budget() {
        PackedBlocks plan = plan(AreaPlanner.Kind.CUBE, 2, 0, 100, 0, 0, 1);
        assertEquals(100, plan.size(), "the cap holds whatever the shape");
        Set<Long> broken = new HashSet<>();
        assertEquals(64, plan.drain(64, broken::add));
        assertEquals(36, plan.remaining());
        assertEquals(36, plan.drain(64, broken::add));
        assertEquals(0, plan.drain(64, broken::add));
        assertEquals(positions(plan), broken, "every planned block once, none twice");
    }

    @Test
    @DisplayName("the visited set holds what it was given, through growth")
    void longSet() {
        AreaPlanner.LongSet set = new AreaPlanner.LongSet(4);
        for (long i = -500; i < 500; i++) assertTrue(set.add(i * 7919));
        for (long i = -500; i < 500; i++) assertFalse(set.add(i * 7919));
    }
}
