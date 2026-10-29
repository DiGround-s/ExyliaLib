package net.exylia.lib.guide.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The walkable-path search on terrain made up here: a floor at y = 0, players standing at y = 1. */
class PathSearchTest {

    private static final int SIZE = 20;

    /** A 20 x 20 field of floor at y 0, open above, unknown outside. */
    private static final class Field implements PathSearch.Terrain {
        final Map<Long, Integer> blocks = new HashMap<>();

        void set(int x, int y, int z, int kind) {
            blocks.put(key(x, y, z), kind);
        }

        /** A wall two blocks tall along x = {@code x}, every z. */
        void wall(int x) {
            for (int z = 0; z < SIZE; z++) {
                set(x, 1, z, PathSearch.WALL);
                set(x, 2, z, PathSearch.WALL);
            }
        }

        @Override
        public int kind(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < -5 || y > 10) {
                return PathSearch.UNKNOWN;
            }
            Integer set = blocks.get(key(x, y, z));
            if (set != null) {
                return set;
            }
            return y <= 0 ? PathSearch.FLOOR : PathSearch.OPEN;
        }

        private static long key(int x, int y, int z) {
            return ((long) x << 40) ^ ((long) y << 20) ^ z;
        }
    }

    private static PathSearch.Result find(Field field, int sx, int sz, int tx, int ty, int tz) {
        return PathSearch.find(field, sx, 1, sz, tx, ty, tz, 1, 20_000);
    }

    @Test
    @DisplayName("open ground: a straight walk")
    void open() {
        PathSearch.Result result = find(new Field(), 2, 10, 12, 1, 10);
        assertTrue(result.reached());
        assertEquals(10, result.cells().size() - 1, 1);
    }

    @Test
    @DisplayName("a wall with a door: through the door, never through the wall")
    void door() {
        Field field = new Field();
        field.wall(8);
        field.set(8, 1, 3, PathSearch.OPEN);
        field.set(8, 2, 3, PathSearch.OPEN);
        PathSearch.Result result = find(field, 2, 10, 14, 1, 10);
        assertTrue(result.reached());
        for (int[] cell : result.cells()) {
            if (cell[0] == 8) {
                assertEquals(3, cell[2], "crossed the wall away from the door");
            }
        }
    }

    @Test
    @DisplayName("stairs up a ledge one block at a time, but never a two-block climb")
    void stairs() {
        Field field = new Field();
        // A platform at y 3 (stand at 4) from x 12, reached by steps at x 10 (top 1) and x 11 (top 2).
        for (int z = 0; z < SIZE; z++) {
            for (int x = 12; x < SIZE; x++) {
                for (int y = 1; y <= 3; y++) {
                    field.set(x, y, z, PathSearch.FLOOR);
                }
            }
        }
        field.set(10, 1, 5, PathSearch.FLOOR);
        field.set(11, 1, 5, PathSearch.FLOOR);
        field.set(11, 2, 5, PathSearch.FLOOR);
        PathSearch.Result result = find(field, 2, 15, 15, 4, 15);
        assertTrue(result.reached());
        boolean usedSteps = result.cells().stream().anyMatch(cell -> cell[0] == 11 && cell[2] == 5);
        assertTrue(usedSteps, "the only way up is the stair");
        for (int i = 1; i < result.cells().size(); i++) {
            assertTrue(result.cells().get(i)[1] - result.cells().get(i - 1)[1] <= 1, "climbed more than a block");
        }
    }

    @Test
    @DisplayName("a gap in the floor is walked around, not over")
    void gap() {
        Field field = new Field();
        // A pit 4 deep across most of the field at x 9-10, crossable only at z 18-19.
        for (int z = 0; z < 18; z++) {
            for (int x = 9; x <= 10; x++) {
                for (int y = -4; y <= 0; y++) {
                    field.set(x, y, z, PathSearch.OPEN);
                }
                field.set(x, -5, z, PathSearch.DANGER);
            }
        }
        PathSearch.Result result = find(field, 2, 5, 16, 1, 5);
        assertTrue(result.reached());
        assertTrue(result.cells().stream().allMatch(cell -> cell[1] == 1), "fell into the pit");
        assertTrue(result.cells().stream().anyMatch(cell -> cell[2] >= 18), "did not go around");
    }

    @Test
    @DisplayName("a target walled off completely is reported unreachable, with the nearest cell")
    void unreachable() {
        Field field = new Field();
        field.wall(8);
        PathSearch.Result result = find(field, 2, 10, 14, 1, 10);
        assertFalse(result.reached());
        assertTrue(result.exhausted());
        List<int[]> cells = result.cells();
        assertEquals(7, cells.getLast()[0], "the nearest cell is against the wall");
    }

    @Test
    @DisplayName("lava is never walked into")
    void danger() {
        Field field = new Field();
        for (int z = 0; z < SIZE; z++) {
            field.set(8, 1, z, PathSearch.DANGER);
        }
        PathSearch.Result result = find(field, 2, 10, 14, 1, 10);
        assertFalse(result.reached());
    }

    @Test
    @DisplayName("a found walk becomes a route at foot height that never jumps more than a block")
    void route() {
        Field field = new Field();
        field.wall(8);
        field.set(8, 1, 3, PathSearch.OPEN);
        field.set(8, 2, 3, PathSearch.OPEN);
        net.exylia.lib.guide.GuidePath path = GuidePaths.search(field, 2, 1, 10, 14, 1, 10);
        assertTrue(path.reached());
        assertFalse(path.unreachable());
        List<org.bukkit.util.Vector> points = path.route().points();
        assertEquals(2.5, points.getFirst().getX(), 1e-9);
        assertTrue(points.stream().allMatch(point -> point.getY() == 1));
        // The thinned line still goes through the door, not the wall.
        net.exylia.lib.guide.Route route = path.route();
        for (double arc = 0; arc <= route.length(); arc += 0.25) {
            net.exylia.lib.guide.Route.Station at = route.at(arc);
            if ((int) Math.floor(at.x()) == 8) {
                assertEquals(3, (int) Math.floor(at.z()), "the route cuts through the wall");
            }
        }
    }

    @Test
    @DisplayName("a walled-off target gives an honest answer: not reached, unreachable")
    void honest() {
        Field field = new Field();
        field.wall(8);
        net.exylia.lib.guide.GuidePath path = GuidePaths.search(field, 2, 1, 10, 14, 1, 10);
        assertFalse(path.reached());
        assertTrue(path.unreachable());
    }

    @Test
    @DisplayName("only chunks along the way are copied, nearest first, and never more than the cap")
    void corridor() {
        List<Long> chunks = GuidePaths.corridor(8, 8, 128, 128);
        assertTrue(chunks.size() <= GuidePaths.MAX_CHUNKS);
        assertEquals(0L, (long) chunks.getFirst(), "the start's chunk comes first");
        // The far corners of the square the walk spans are off the corridor.
        assertFalse(chunks.contains((7L << 32) | 0L), "a corner chunk far from the line was copied");
    }
}
