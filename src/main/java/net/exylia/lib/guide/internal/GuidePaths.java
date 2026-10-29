package net.exylia.lib.guide.internal;

import net.exylia.lib.guide.GuidePath;
import net.exylia.lib.guide.Route;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Finds the way a player can walk, without ever touching the world off its
 * own threads.
 *
 * <h2>Three hops</h2>
 * The chunks along the way are copied ({@link ChunkSnapshot}) on the thread
 * that owns each one &mdash; the main thread on Paper, its region on Folia &mdash;
 * only if they are already loaded: nothing is ever loaded for a path. The
 * search ({@link PathSearch}) then runs on an async thread over the copies, and
 * the caller is handed the result to take back to the player's own thread.
 *
 * <h2>Bounded</h2>
 * Targets more than {@link #MAX_RANGE} blocks away are not searched. Only the
 * chunks within {@link #CORRIDOR} blocks of the straight line are copied (at
 * most {@link #MAX_CHUNKS}), so a detour wider than that is not found; at most
 * {@link #BUDGET} cells are expanded, and the copies must arrive within
 * {@link #CAPTURE_TIMEOUT_MS}. An answer is kept for
 * {@link #CACHE_MILLIS} per start and target block, so a burst of asks for
 * the same walk costs one search.
 */
@ApiStatus.Internal
public final class GuidePaths {

    static final int MAX_RANGE = 128;
    static final int CORRIDOR = 20;
    static final int MAX_CHUNKS = 100;
    static final int BUDGET = 40_000;
    static final long CAPTURE_TIMEOUT_MS = 2_000;
    static final long CACHE_MILLIS = 10_000;
    /** Points of the walk closer than this to the straight line between their neighbours are dropped. */
    private static final double SMOOTHING = 0.3;

    private record Key(String world, long from, long to) {
    }

    private record Cached(GuidePath path, long at) {
    }

    private static final Map<Key, Cached> CACHE = new ConcurrentHashMap<>();

    private GuidePaths() {
    }

    /**
     * Finds the way from one place to another. Any thread; the future
     * completes on an async thread.
     */
    public static CompletableFuture<GuidePath> find(Plugin plugin, Location from, Location to) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return CompletableFuture.completedFuture(GuidePath.NONE);
        }
        int sx = from.getBlockX();
        int sy = from.getBlockY();
        int sz = from.getBlockZ();
        int tx = to.getBlockX();
        int ty = to.getBlockY();
        int tz = to.getBlockZ();
        double flat = Math.hypot(tx - sx, tz - sz);
        if (flat > MAX_RANGE) {
            return CompletableFuture.completedFuture(GuidePath.NONE);
        }
        Key key = new Key(world.getName(), pack(sx, sy, sz), pack(tx, ty, tz));
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(key);
        if (cached != null && now - cached.at() < CACHE_MILLIS) {
            return CompletableFuture.completedFuture(cached.path());
        }
        if (CACHE.size() > 256) {
            CACHE.values().removeIf(old -> now - old.at() >= CACHE_MILLIS);
        }
        TaskScheduler tasks = Tasks.of(plugin);
        Map<Long, CompletableFuture<ChunkSnapshot>> copies = new HashMap<>();
        for (long chunk : corridor(sx, sz, tx, tz)) {
            int cx = (int) (chunk >> 32);
            int cz = (int) chunk;
            CompletableFuture<ChunkSnapshot> copy = new CompletableFuture<>();
            copies.put(chunk, copy);
            tasks.runAtLocation(new Location(world, (cx << 4) + 8, 64, (cz << 4) + 8), () -> {
                try {
                    copy.complete(world.isChunkLoaded(cx, cz)
                            ? world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false) : null);
                } catch (Throwable failed) {
                    copy.complete(null);
                }
            });
        }
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        return CompletableFuture.allOf(copies.values().toArray(CompletableFuture[]::new))
                .orTimeout(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .handleAsync((done, failure) -> {
                    Map<Long, ChunkSnapshot> chunks = new HashMap<>();
                    copies.forEach((chunk, copy) -> {
                        ChunkSnapshot snapshot = copy.getNow(null);
                        if (snapshot != null) {
                            chunks.put(chunk, snapshot);
                        }
                    });
                    GuidePath path = search(new Snapshots(chunks, minY, maxY), sx, sy, sz, tx, ty, tz);
                    CACHE.put(key, new Cached(path, System.currentTimeMillis()));
                    return path;
                });
    }

    /** The search, from block coordinates, over any terrain. */
    static GuidePath search(PathSearch.Terrain terrain, int sx, int sy, int sz, int tx, int ty, int tz) {
        int startY = stand(terrain, sx, sy, sz);
        int targetY = stand(terrain, tx, ty, tz);
        if (startY == Integer.MIN_VALUE) {
            return GuidePath.NONE;
        }
        PathSearch.Result result = PathSearch.find(terrain, sx, startY, sz, tx,
                targetY == Integer.MIN_VALUE ? ty : targetY, tz, 1.5, BUDGET);
        if (result.cells().size() < 2) {
            return new GuidePath(null, result.reached(), result.exhausted() && !result.reached());
        }
        return new GuidePath(route(result.cells()), result.reached(), result.exhausted() && !result.reached());
    }

    /** A cell path as a route through the cells' centres, at foot height, with straight runs thinned. */
    static Route route(List<int[]> cells) {
        List<Vector> points = new ArrayList<>(cells.size());
        for (int[] cell : cells) {
            points.add(new Vector(cell[0] + 0.5, cell[1], cell[2] + 0.5));
        }
        return Route.of(points).simplified(SMOOTHING);
    }

    /** The standing cell at or next to a block: its own, one up (a slab, a carpet), or one or two down. */
    private static int stand(PathSearch.Terrain terrain, int x, int y, int z) {
        for (int dy : new int[]{0, 1, -1, -2}) {
            if (PathSearch.standable(terrain, x, y + dy, z)) {
                return y + dy;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** The chunks within the corridor of the straight line, nearest the start first, capped. */
    static List<Long> corridor(int sx, int sz, int tx, int tz) {
        int minCx = (Math.min(sx, tx) - CORRIDOR) >> 4;
        int maxCx = (Math.max(sx, tx) + CORRIDOR) >> 4;
        int minCz = (Math.min(sz, tz) - CORRIDOR) >> 4;
        int maxCz = (Math.max(sz, tz) + CORRIDOR) >> 4;
        List<long[]> found = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                double gap = segmentGap((cx << 4) + 8, (cz << 4) + 8, sx, sz, tx, tz);
                // A chunk's centre is at most 11.4 blocks from any of its blocks.
                if (gap <= CORRIDOR + 12) {
                    found.add(new long[]{((long) cx << 32) | (cz & 0xFFFFFFFFL),
                            (long) Math.hypot((cx << 4) + 8 - sx, (cz << 4) + 8 - sz)});
                }
            }
        }
        found.sort((a, b) -> Long.compare(a[1], b[1]));
        List<Long> chunks = new ArrayList<>();
        for (int index = 0; index < Math.min(MAX_CHUNKS, found.size()); index++) {
            chunks.add(found.get(index)[0]);
        }
        return chunks;
    }

    private static double segmentGap(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared <= 0 ? 0 : Math.clamp(((px - ax) * dx + (pz - az) * dz) / lengthSquared, 0, 1);
        return Math.hypot(px - (ax + dx * t), pz - (az + dz * t));
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** The copied chunks, read as walking terrain. */
    private record Snapshots(Map<Long, ChunkSnapshot> chunks, int minY, int maxY) implements PathSearch.Terrain {

        @Override
        public int kind(int x, int y, int z) {
            if (y < minY) {
                return PathSearch.UNKNOWN;
            }
            if (y >= maxY) {
                return PathSearch.OPEN;
            }
            ChunkSnapshot chunk = chunks.get(((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL));
            if (chunk == null) {
                return PathSearch.UNKNOWN;
            }
            return classify(chunk.getBlockType(x & 15, y, z & 15));
        }
    }

    /** What a block is to somebody walking. */
    static int classify(Material type) {
        if (type.isAir()) {
            return PathSearch.OPEN;
        }
        String name = type.name();
        switch (name) {
            case "WATER", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS" -> {
                return PathSearch.WATER;
            }
            case "LAVA", "FIRE", "SOUL_FIRE", "CACTUS", "MAGMA_BLOCK", "POWDER_SNOW", "SWEET_BERRY_BUSH",
                 "CAMPFIRE", "SOUL_CAMPFIRE", "WITHER_ROSE", "COBWEB", "POINTED_DRIPSTONE" -> {
                return PathSearch.DANGER;
            }
            case "IRON_DOOR", "IRON_TRAPDOOR", "IRON_BARS", "CHAIN" -> {
                return PathSearch.WALL;
            }
            default -> {
            }
        }
        if (name.endsWith("_DOOR") || name.endsWith("_FENCE_GATE") || name.endsWith("_TRAPDOOR")
                || name.endsWith("_CARPET") || name.equals("SNOW")) {
            return PathSearch.OPEN;
        }
        if (name.endsWith("_FENCE") || (name.endsWith("_WALL") && !name.contains("_WALL_"))
                || name.endsWith("_PANE") || name.equals("GLASS_PANE")) {
            return PathSearch.WALL;
        }
        return type.isSolid() ? PathSearch.FLOOR : PathSearch.OPEN;
    }

    /** Forgets every kept answer, on shutdown. */
    public static void clear() {
        CACHE.clear();
    }
}
