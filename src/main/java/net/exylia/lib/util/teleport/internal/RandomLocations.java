package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.teleport.RandomArea;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Finding somewhere out in the world that a player can be dropped into.
 *
 * <h2>Why the radius goes through a square root</h2>
 * A ring is not a line. Twice as far out is four times as much ground, so
 * picking the distance uniformly between the minimum and the maximum — the
 * obvious {@code random(min, max)} — puts a quarter of everybody in the inner
 * quarter of the area and clusters them near the centre. Squaring the bounds,
 * picking uniformly between those, and taking the square root back is what
 * makes every square metre of the ring equally likely, which is what "random"
 * has to mean for a feature whose whole job is spreading people out.
 *
 * <h2>Why every attempt is a fresh asynchronous chunk load</h2>
 * Reading a block in a chunk that is not loaded loads it, synchronously, on the
 * thread that asked — and out in unexplored terrain that means generating it.
 * That is a stall the entire server feels, for one player who typed a command.
 * Each candidate is therefore loaded through {@code getChunkAtAsync} and only
 * examined once it has arrived; a failed candidate schedules the next one
 * rather than looping, so nothing here ever waits.
 *
 * <h2>Why the safety rules are not repeated here</h2>
 * {@link SafeLocations} already knows what a survivable landing is, and two
 * copies of that list drift: the day somebody adds powder snow to one, the
 * other keeps dropping players into it. This picks the coordinates and asks
 * that about them.
 */
@ApiStatus.Internal
public final class RandomLocations {

    private RandomLocations() {
        throw new AssertionError("No instances.");
    }

    /**
     * Looks for somewhere in the area a player can land.
     *
     * <p><b>Threading:</b> safe to call from anywhere. Each candidate's chunk
     * is fetched asynchronously and inspected on the thread owning it, so
     * nothing here reads a block from the wrong thread and nothing blocks.
     *
     * @param area        where to look
     * @param maxAttempts how many candidates to try before giving up
     * @param safeRadius  how far around a candidate to look for a safe block
     * @param safeAttempts how many blocks that search may check
     * @param tasks       the asking plugin's scheduler
     * @param debug       where a broken world is reported
     * @return the landing spot, or {@code null} when every attempt was used up
     */
    public static @NotNull CompletableFuture<@Nullable Location> search(
            @NotNull RandomArea area, int maxAttempts, int safeRadius, int safeAttempts,
            @NotNull TaskScheduler tasks, @NotNull Debug debug) {

        CompletableFuture<Location> found = new CompletableFuture<>();
        attempt(area, Math.max(1, maxAttempts), safeRadius, safeAttempts, tasks, debug, found);
        return found;
    }

    /**
     * One candidate, and the next one if it does not work out.
     *
     * <p>Written as a chain rather than a loop because every step of it waits
     * for a chunk: a loop would have to block for each one, which is the exact
     * stall this class exists to avoid.
     */
    private static void attempt(RandomArea area, int attemptsLeft, int safeRadius, int safeAttempts,
                                TaskScheduler tasks, Debug debug,
                                CompletableFuture<Location> found) {
        if (attemptsLeft <= 0) {
            found.complete(null);
            return;
        }

        int[] candidate = pick(area);
        int x = candidate[0];
        int z = candidate[1];
        World world = area.world();

        CompletableFuture<Chunk> chunk;
        try {
            chunk = world.getChunkAtAsync(x >> 4, z >> 4);
        } catch (RuntimeException refused) {
            debug.error("Could not load a chunk for a random teleport in " + world.getName(), refused);
            chunk = null;
        }
        if (chunk == null) {
            // A world that will not hand one over is not a reason to stop:
            // another candidate may be in a region that answers perfectly well.
            attempt(area, attemptsLeft - 1, safeRadius, safeAttempts, tasks, debug, found);
            return;
        }

        chunk.whenComplete((loaded, failure) -> {
            if (failure != null) {
                debug.error("Could not load a chunk for a random teleport in "
                        + world.getName(), failure);
                attempt(area, attemptsLeft - 1, safeRadius, safeAttempts, tasks, debug, found);
                return;
            }
            Location candidateLocation = new Location(world, x + 0.5, 64, z + 0.5);
            // Blocks and biomes are read here and nowhere else: this is the one
            // thread allowed to answer questions about that piece of the world.
            tasks.runAtLocation(candidateLocation, () -> {
                Location landing = inspect(area, x, z, safeRadius, safeAttempts, debug);
                if (landing != null) {
                    found.complete(landing);
                    return;
                }
                attempt(area, attemptsLeft - 1, safeRadius, safeAttempts, tasks, debug, found);
            });
        });
    }

    /**
     * Whether this column is somewhere to put somebody, and where exactly.
     *
     * <p>Runs on the thread owning the column. Everything it can fail on — a
     * world that answers nothing, a biome the area refuses, no survivable block
     * nearby — is one candidate wasted, never an exception.
     */
    private static @Nullable Location inspect(RandomArea area, int x, int z,
                                              int safeRadius, int safeAttempts, Debug debug) {
        World world = area.world();
        try {
            if (isBlocked(area, world, x, z)) {
                return null;
            }
            Block highest = world.getHighestBlockAt(x, z);
            if (highest == null) {
                return null;
            }
            // One above the surface is where a player stands; the surface block
            // itself is the floor SafeLocations wants underneath them.
            Location standing = new Location(world, x + 0.5, highest.getY() + 1.0, z + 0.5);
            return SafeLocations.nearest(standing, safeRadius, safeAttempts);
        } catch (RuntimeException unreadable) {
            debug.error("Could not read a candidate for a random teleport in "
                    + world.getName(), unreadable);
            return null;
        }
    }

    /**
     * Whether the area refuses this column's biome.
     *
     * <p>Compared by name because the biome registry stopped being an enum, so
     * a configured name the server does not have has to be a name that never
     * matches rather than a lookup that throws. Both the plain name and the
     * namespaced one are offered, since a config may reasonably write either.
     */
    private static boolean isBlocked(RandomArea area, World world, int x, int z) {
        if (area.blockedBiomes().isEmpty()) {
            return false;
        }
        Biome biome = world.getBiome(x, z);
        if (biome == null) {
            return false;
        }
        org.bukkit.NamespacedKey key = biome.getKey();
        return area.blocks(key.getKey()) || area.blocks(key.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * A column somewhere in the ring, chosen so every square metre is as likely
     * as every other.
     *
     * @return the x and z of the candidate
     */
    private static int[] pick(RandomArea area) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double min = area.minRadius();
        double max = area.maxRadius();
        // The square root is the whole point: without it the distance is
        // uniform along a line rather than across a surface, and everybody
        // lands near the centre.
        double radius = Math.sqrt(min * min + random.nextDouble() * (max * max - min * min));
        double angle = random.nextDouble() * Math.PI * 2;
        long x = area.centreX() + Math.round(radius * Math.cos(angle));
        long z = area.centreZ() + Math.round(radius * Math.sin(angle));
        return new int[]{clampToWorld(x), clampToWorld(z)};
    }

    /** Keeps a candidate inside coordinates the world can actually address. */
    private static int clampToWorld(long value) {
        return (int) Math.clamp(value, -RandomArea.MAX_RADIUS, RandomArea.MAX_RADIUS);
    }
}
