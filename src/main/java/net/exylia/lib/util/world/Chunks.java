package net.exylia.lib.util.world;

import net.exylia.lib.task.TaskScheduler;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Reading a chunk without ever loading it on a server thread.
 *
 * <pre>{@code
 * Chunks.snapshot(tasks, world, x >> 4, z >> 4).thenAccept(chunk ->
 *         tasks.runAsync(() -> scan(chunk.getBlockType(x & 15, y, z & 15)))); // immutable: read it anywhere
 * }</pre>
 *
 * <p>{@code world.getBlockAt(x, y, z).getType()} on a chunk that is not loaded
 * loads it synchronously on the calling thread. On Folia that thread is a
 * region, and a region waiting on a chunk load is a region the watchdog
 * reports as not responding. Here an unloaded chunk is loaded with
 * {@code getChunkAtAsync}; only the snapshot itself, a copy of the chunk's
 * palettes, is taken on the thread that owns the chunk.
 *
 * @since 1.466.0
 */
public final class Chunks {

    /** Async loads tried before giving up on a chunk that unloads again before it is read. */
    private static final int LOADS = 3;

    private Chunks() {
    }

    /**
     * A snapshot of one chunk, blocks only (no biomes, no height map).
     *
     * <p>The future completes on the thread that owns the chunk; reading the
     * snapshot is safe from any thread, so move a large scan off it. It
     * completes exceptionally when the chunk fails to load, or unloads again
     * before it can be read three times in a row. Only a server whose API lacks
     * {@code getChunkAtAsync} (plain Spigot) loads synchronously, as it has no
     * other way.
     *
     * @param tasks  the calling plugin's scheduler
     * @param world  the world the chunk is in
     * @param chunkX chunk coordinate (block X {@code >> 4})
     * @param chunkZ chunk coordinate (block Z {@code >> 4})
     */
    public static @NotNull CompletableFuture<ChunkSnapshot> snapshot(@NotNull TaskScheduler tasks, @NotNull World world,
                                                                      int chunkX, int chunkZ) {
        CompletableFuture<ChunkSnapshot> result = new CompletableFuture<>();
        attempt(tasks, world, chunkX, chunkZ, LOADS, result);
        return result;
    }

    private static void attempt(TaskScheduler tasks, World world, int chunkX, int chunkZ, int loadsLeft,
                                CompletableFuture<ChunkSnapshot> result) {
        Location owner = new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
        Runnable read = () -> {
            try {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    result.complete(world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false));
                    return;
                }
                if (loadsLeft <= 0) {
                    result.completeExceptionally(new IllegalStateException("chunk " + chunkX + "," + chunkZ
                            + " in " + world.getName() + " unloaded before it could be read"));
                    return;
                }
                CompletableFuture<?> loading;
                try {
                    loading = world.getChunkAtAsync(chunkX, chunkZ);
                } catch (LinkageError | UnsupportedOperationException noAsyncApi) {
                    result.complete(world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false));
                    return;
                }
                // Back through the owner: where the load completes is the server's business.
                loading.whenComplete((loaded, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    attempt(tasks, world, chunkX, chunkZ, loadsLeft - 1, result);
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        if (tasks.isOwnedBy(owner)) {
            read.run();
        } else {
            tasks.runAtLocation(owner, read);
        }
    }
}
