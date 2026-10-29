package net.exylia.lib.guide.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over the cells a player can stand in, on a frozen copy of the terrain.
 *
 * <p>Pure: it reads a {@link Terrain} and nothing else, so it runs off every
 * server thread and is tested on a grid made up in the test.
 *
 * <h2>What counts as walkable</h2>
 * A cell is a place to stand when its feet and head blocks are open (air,
 * plants, carpets, snow layers, doors, gates) or water, and the block under
 * it is a floor &mdash; or the feet are in water, which is swum through at a
 * cost. From one, a player walks to any of the eight cells around at the same
 * height, steps up one block (a slab or a stair is a block they step onto)
 * with room for their head, or drops up to {@link #MAX_DROP} blocks into an
 * open column. A diagonal needs both cells it squeezes between to be open, so
 * a path never cuts a wall's corner. Lava, fire, cactus, magma, powder snow
 * and berry bushes are never walked into or stood on.
 *
 * <h2>Bounded</h2>
 * At most {@code budget} cells are expanded. A terrain answers
 * {@link #UNKNOWN} outside what was copied, which is never walked, so the
 * search can never reach past it.
 */
final class PathSearch {

    /** What a block is, for walking. */
    static final int OPEN = 0;
    static final int FLOOR = 1;
    /** Blocks movement and is not stood on: a fence, a wall, a pane, bars. */
    static final int WALL = 2;
    static final int WATER = 3;
    static final int DANGER = 4;
    static final int UNKNOWN = 5;

    static final int MAX_DROP = 3;

    private static final double DIAGONAL = Math.sqrt(2);
    private static final double STEP_UP = 0.6;
    private static final double PER_DROP = 0.2;
    private static final double SWIM = 2.0;

    /** The blocks a search reads. */
    interface Terrain {
        int kind(int x, int y, int z);
    }

    /**
     * How a search ended.
     *
     * @param cells     the cells from the start to the target, or to the cell that got nearest it
     * @param reached   whether the last cell is within reach of the target
     * @param exhausted whether every cell reachable in the copied terrain was tried: the target is
     *                  not reachable from there, as opposed to the search running out of budget
     */
    record Result(List<int[]> cells, boolean reached, boolean exhausted) {
    }

    private PathSearch() {
    }

    /**
     * Finds the way from one standing cell to another.
     *
     * @param reach  how close (in blocks, horizontally, and within two vertically) counts as there
     * @param budget the most cells expanded
     */
    static Result find(Terrain terrain, int sx, int sy, int sz, int tx, int ty, int tz, double reach, int budget) {
        Map<Long, Node> seen = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Node start = new Node(sx, sy, sz, null, 0, heuristic(sx, sy, sz, tx, ty, tz));
        seen.put(key(sx, sy, sz), start);
        open.add(start);
        Node best = start;
        int expanded = 0;
        while (!open.isEmpty()) {
            Node at = open.poll();
            if (at.closed) {
                continue;
            }
            at.closed = true;
            if (at.h < best.h) {
                best = at;
            }
            if (near(at, tx, ty, tz, reach)) {
                return new Result(cells(at), true, false);
            }
            if (++expanded > budget) {
                return new Result(cells(best), false, false);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = at.x + dx;
                    int nz = at.z + dz;
                    if (dx != 0 && dz != 0
                            && (!clear(terrain, at.x + dx, at.y, at.z) || !clear(terrain, at.x, at.y, at.z + dz))) {
                        continue;
                    }
                    int ny = landing(terrain, at.x, at.y, at.z, nx, nz);
                    if (ny == Integer.MIN_VALUE) {
                        continue;
                    }
                    double step = (dx != 0 && dz != 0 ? DIAGONAL : 1)
                            + (ny > at.y ? STEP_UP : (at.y - ny) * PER_DROP)
                            + (terrain.kind(nx, ny, nz) == WATER ? SWIM : 0);
                    double g = at.g + step;
                    long k = key(nx, ny, nz);
                    Node known = seen.get(k);
                    if (known != null && (known.closed || known.g <= g)) {
                        continue;
                    }
                    Node next = new Node(nx, ny, nz, at, g, heuristic(nx, ny, nz, tx, ty, tz));
                    seen.put(k, next);
                    open.add(next);
                }
            }
        }
        return new Result(cells(best), false, true);
    }

    /** Where a player at {@code (x, y, z)} ends up moving into column {@code (nx, nz)}, or MIN_VALUE for nowhere. */
    private static int landing(Terrain terrain, int x, int y, int z, int nx, int nz) {
        if (standable(terrain, nx, y, nz)) {
            return y;
        }
        // Up a block: onto it, with room above both heads.
        if (passable(terrain.kind(x, y + 2, z)) && standable(terrain, nx, y + 1, nz)) {
            return y + 1;
        }
        // Down: the column ahead must be open from head height to where they land.
        if (!clear(terrain, nx, y, nz)) {
            return Integer.MIN_VALUE;
        }
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            int ny = y - drop;
            if (standable(terrain, nx, ny, nz)) {
                return ny;
            }
            if (!passable(terrain.kind(nx, ny, nz))) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Feet and head at {@code y} open. */
    private static boolean clear(Terrain terrain, int x, int y, int z) {
        return passable(terrain.kind(x, y, z)) && passable(terrain.kind(x, y + 1, z));
    }

    static boolean standable(Terrain terrain, int x, int y, int z) {
        int feet = terrain.kind(x, y, z);
        if (!passable(feet) || !passable(terrain.kind(x, y + 1, z))) {
            return false;
        }
        return feet == WATER || terrain.kind(x, y - 1, z) == FLOOR;
    }

    private static boolean passable(int kind) {
        return kind == OPEN || kind == WATER;
    }

    private static boolean near(Node node, int tx, int ty, int tz, double reach) {
        double dx = node.x - tx;
        double dz = node.z - tz;
        return dx * dx + dz * dz <= reach * reach && Math.abs(node.y - ty) <= 2;
    }

    private static double heuristic(int x, int y, int z, int tx, int ty, int tz) {
        double dx = x - tx;
        double dy = y - ty;
        double dz = z - tz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<int[]> cells(Node last) {
        List<int[]> cells = new ArrayList<>();
        for (Node node = last; node != null; node = node.parent) {
            cells.add(new int[]{node.x, node.y, node.z});
        }
        Collections.reverse(cells);
        return cells;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static final class Node {
        final int x;
        final int y;
        final int z;
        final Node parent;
        final double g;
        final double h;
        final double f;
        boolean closed;

        Node(int x, int y, int z, Node parent, double g, double h) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.g = g;
            this.h = h;
            this.f = g + h;
        }
    }
}
