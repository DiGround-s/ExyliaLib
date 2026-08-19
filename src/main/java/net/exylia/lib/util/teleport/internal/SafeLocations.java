package net.exylia.lib.util.teleport.internal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finding somewhere near a destination that a player can survive landing on.
 *
 * <h2>Why it is bounded</h2>
 * This reads blocks, so it runs on the thread owning the location and every
 * block it checks is a tick the server is not spending on anything else. The
 * attempt limit is the whole design: a search that gives up is a message to one
 * player, while a search that scans a world is a stall everybody feels.
 *
 * <h2>Why nearest-first is not the same as shell-by-shell</h2>
 * Checking every block at a Chebyshev distance of one, then two, and so on,
 * does not visit them in order of actual distance: the corner of the first
 * shell is further away than the face of the second. Offsets are therefore
 * sorted by squared distance, so the first spot that passes really is the
 * closest one that does, and the player lands where they were sent rather than
 * diagonally off it.
 */
@ApiStatus.Internal
public final class SafeLocations {

    /**
     * Blocks that are solid enough to stand on and will still kill you.
     *
     * <p>Named as enum constants rather than looked up by string: several of
     * these types stopped being enum values in 1.21, and a name resolved at
     * runtime fails on the server rather than in the compiler.
     */
    private static final Set<Material> LETHAL = EnumSet.of(
            Material.LAVA,
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
            Material.POWDER_SNOW,
            Material.END_PORTAL,
            Material.NETHER_PORTAL,
            Material.VOID_AIR);

    /**
     * Offsets to try, nearest first, by the radius they were built for.
     *
     * <p>Sorting is the expensive part and the answer never changes, so it is
     * done once per radius rather than once per teleport. At most a few dozen
     * entries, since the radius that matters is bounded by the attempt limit.
     */
    private static final Map<Integer, int[][]> ORDERED = new ConcurrentHashMap<>();

    private SafeLocations() {
        throw new AssertionError("No instances.");
    }

    /**
     * The nearest spot to {@code origin} a player can be put down on.
     *
     * <p><b>Threading:</b> reads blocks, so the caller must already be on the
     * thread owning {@code origin}. Nothing here hops threads on its own,
     * because a hop would return an answer about a world that has since moved.
     *
     * @param origin      where the teleport was aimed
     * @param radius      how far to look, in blocks
     * @param maxAttempts how many blocks may be checked before giving up
     * @return the safe spot, centred on its block and facing the way the origin
     *         faced, or {@code null} when the budget ran out without one
     */
    public static @Nullable Location nearest(@NotNull Location origin, int radius, int maxAttempts) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        int budget = Math.max(1, maxAttempts);
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();

        int checked = 0;
        for (int[] offset : orderedOffsets(effectiveRadius(radius, budget))) {
            if (checked >= budget) {
                // Out of budget rather than out of places to look. Reported as
                // "no safe spot" either way: dropping a player somewhere that
                // was never checked is worse than refusing the teleport.
                return null;
            }
            checked++;
            int x = originX + offset[0];
            int y = originY + offset[1];
            int z = originZ + offset[2];
            if (!isSafe(world, x, y, z)) {
                continue;
            }
            // Centred on the block, because landing on the corner of one drops
            // a player through the gap between it and its neighbour. The
            // direction is the origin's: it is what the teleport asked for, and
            // the search has no opinion about which way to face.
            return new Location(world, x + 0.5, y, z + 0.5, origin.getYaw(), origin.getPitch());
        }
        return null;
    }

    /**
     * Whether a player standing here would live.
     *
     * <p>Two blocks of room for the body, something solid under the feet, and
     * that something not being one of the surfaces that is solid and still
     * fatal.
     */
    private static boolean isSafe(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block floor = world.getBlockAt(x, y - 1, z);
        if (feet == null || head == null || floor == null) {
            // A world that cannot answer is a world we must not drop anybody
            // into. Reachable in tests and on a chunk that is not loaded.
            return false;
        }
        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (LETHAL.contains(feet.getType()) || LETHAL.contains(head.getType())) {
            // Fire and a nether portal are both passable, so the body checks
            // alone would call standing in one of them safe.
            return false;
        }
        Material under = floor.getType();
        return under.isSolid() && !LETHAL.contains(under);
    }

    /**
     * The radius actually worth generating offsets for.
     *
     * <p>Only the nearest {@code budget} offsets can ever be checked, so
     * building the rest of a large radius is work whose answer is thrown away.
     * Capping here is what keeps a radius of 32 from producing a quarter of a
     * million offsets to sort for a search that will look at 32 of them.
     */
    private static int effectiveRadius(int radius, int budget) {
        int wanted = Math.max(0, radius);
        for (int r = 0; r < wanted; r++) {
            long inside = (2L * r + 1);
            if (inside * inside * inside >= budget) {
                return r;
            }
        }
        return wanted;
    }

    /** Every offset within a radius, nearest first, built once per radius. */
    private static int[][] orderedOffsets(int radius) {
        return ORDERED.computeIfAbsent(radius, SafeLocations::buildOffsets);
    }

    private static int[][] buildOffsets(int radius) {
        int side = 2 * radius + 1;
        int[][] offsets = new int[side * side * side][];
        int index = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    offsets[index++] = new int[]{dx, dy, dz};
                }
            }
        }
        java.util.Arrays.sort(offsets, (a, b) -> {
            int byDistance = Integer.compare(squared(a), squared(b));
            if (byDistance != 0) {
                return byDistance;
            }
            // Ties broken downwards first: at equal distance, the floor below
            // is a better landing than the ceiling above.
            return Integer.compare(a[1], b[1]);
        });
        return offsets;
    }

    private static int squared(int[] offset) {
        return offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2];
    }
}
