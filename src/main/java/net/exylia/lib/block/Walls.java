package net.exylia.lib.block;

import net.exylia.lib.block.internal.WallFile;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BlockVector;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Real blocks put up for a while — the walls a duel room closes with — that
 * always come down, a crash included.
 *
 * <pre>{@code
 * Walls walls = Walls.of(this);
 *
 * // onEnable, before any wall goes up: whatever a crash left standing comes down
 * walls.recover(id -> arenas.regenerate(id));
 *
 * walls.raise("duel:" + room.id(), world, room.shell(), Material.BARRIER)
 *         .thenAccept(wall -> match.wall = wall);
 *
 * match.wall.lower();                       // the fight is over
 * match.wall.lowerNow();                    // onDisable: what this thread can write
 * }</pre>
 *
 * <p>Only a block that is air or replaceable becomes the wall, and what it was
 * is written to {@code <data folder>/walls/} before anything else happens to
 * it. Lowering puts it back only where the wall's material still stands, so a
 * block somebody changed since is left as they left it. The file is local to
 * the server, which is where the blocks are: servers of a network sharing a
 * database never take each other's walls down.
 *
 * <p>Blocks are written on the thread that owns their chunk, on Folia as on
 * Bukkit. Guarding the wall against breaking is the plugin's
 * ({@link Wall#contains} answers from memory on any thread).
 *
 * @since 1.458.0
 */
public final class Walls {

    private static final Map<String, Walls> BY_PLUGIN = new ConcurrentHashMap<>();

    /** How long {@link Wall#lowerNow} waits for a file write still in flight before deleting anyway. */
    private static final long WRITE_WAIT_SECONDS = 5;

    private final Plugin plugin;
    private final File folder;
    private final Map<String, Wall> standing = new ConcurrentHashMap<>();

    private Walls(Plugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "walls");
    }

    /** That plugin's walls, the same instance every time. */
    public static @NotNull Walls of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new Walls(plugin));
    }

    /** Forgets a disabled plugin's handles. Called by the library; the files stay for its next start. */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
    }

    /** Where the files are: {@code <data folder>/walls}. */
    public @NotNull File folder() {
        return folder;
    }

    /**
     * Puts a wall up.
     *
     * @param id       unique among this plugin's walls standing now; the room
     *                 or arena it closes
     * @param world    where
     * @param blocks   every position it covers
     * @param material what it is made of
     * @return the wall once every chunk is written; fails when a wall with that
     *         id is already standing
     */
    public @NotNull CompletableFuture<Wall> raise(@NotNull String id, @NotNull World world,
                                                  @NotNull Collection<? extends BlockVector> blocks,
                                                  @NotNull Material material) {
        if (id.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("blank wall id"));
        if (standing.containsKey(id)) {
            return CompletableFuture.failedFuture(new IllegalStateException("wall '" + id + "' is already standing"));
        }
        List<WallFile.Placed> placed = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (List<BlockVector> chunk : byChunk(blocks).values()) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            work.add(done);
            BlockVector first = chunk.getFirst();
            tasks().runAtLocation(new Location(world, first.getBlockX(), first.getBlockY(), first.getBlockZ()), () -> {
                try {
                    for (BlockVector position : chunk) {
                        Block block = world.getBlockAt(position.getBlockX(), position.getBlockY(), position.getBlockZ());
                        if (block.getType() == material || !block.isReplaceable()) continue;
                        placed.add(new WallFile.Placed(block.getX(), block.getY(), block.getZ(),
                                block.getBlockData().getAsString()));
                        block.setType(material, false);
                    }
                } catch (RuntimeException failure) {
                    // Whatever went up is recorded, so it still comes down.
                    Debug.of(plugin).error("Part of the wall '" + id + "' could not be raised.", failure);
                } finally {
                    done.complete(null);
                }
            });
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<WallFile.Placed> raised = List.copyOf(placed);
            CompletableFuture<Void> written = new CompletableFuture<>();
            Wall wall = new Wall(this, id, world, material, raised, written);
            standing.put(id, wall);
            if (raised.isEmpty()) {
                written.complete(null);
                return wall;
            }
            tasks().runAsync(() -> {
                try {
                    WallFile.write(WallFile.of(folder, id),
                            new WallFile.Contents(id, world.getName(), material.name(), raised));
                } catch (IOException failure) {
                    Debug.of(plugin).error("The wall '" + id + "' could not be recorded; a crash now would leave it standing.",
                            failure);
                } finally {
                    written.complete(null);
                }
            });
            return wall;
        });
    }

    /**
     * Takes down every wall a crash or a stop left standing, from the files of
     * the last run. Call it once on enable, before raising any; a wall raised in
     * this run is never touched.
     *
     * <p>A wall in a world that is not loaded keeps its file for a start that
     * has it, and so does one that could not be fully put back.
     *
     * @param lowered told each wall's id once it was taken down (or tried), on
     *                whichever thread finished it: what the plugin rebuilds
     *                behind it, such as the arena it closed
     * @return completes once every wall was tried; never exceptionally
     */
    public @NotNull CompletableFuture<Void> recover(@NotNull Consumer<String> lowered) {
        CompletableFuture<Void> all = new CompletableFuture<>();
        tasks().runAsync(() -> {
            List<CompletableFuture<?>> work = new ArrayList<>();
            for (File file : WallFile.list(folder)) {
                WallFile.Contents contents;
                try {
                    contents = WallFile.read(file);
                } catch (IOException failure) {
                    Debug.of(plugin).error("Could not read " + file.getName() + "; that wall stays up.", failure);
                    continue;
                }
                if (contents == null) {
                    delete(file);
                    continue;
                }
                if (standing.containsKey(contents.id())) continue;
                World world = Bukkit.getWorld(contents.world());
                Material material = Material.matchMaterial(contents.material());
                if (world == null || material == null) continue;
                work.add(restore(world, contents.blocks(), material, false).handle((restored, failure) -> {
                    if (Boolean.TRUE.equals(restored)) delete(file);
                    try {
                        lowered.accept(contents.id());
                    } catch (RuntimeException failed) {
                        Debug.of(plugin).error("Recovering the wall '" + contents.id() + "' failed.", failed);
                    }
                    return null;
                }));
            }
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, failure) -> all.complete(null));
        });
        return all;
    }

    CompletableFuture<Boolean> lower(Wall wall) {
        return restore(wall.world(), wall.blocks(), wall.material(), false).thenCompose(all -> {
            if (!all) return CompletableFuture.completedFuture(false);
            return wall.written.thenApplyAsync(ignored -> {
                forget(wall);
                return true;
            }, task -> tasks().runAsync(task));
        });
    }

    boolean lowerNow(Wall wall) {
        boolean all = restore(wall.world(), wall.blocks(), wall.material(), true).join();
        if (all) {
            wall.written.completeOnTimeout(null, WRITE_WAIT_SECONDS, TimeUnit.SECONDS).join();
            forget(wall);
        }
        return all;
    }

    private void forget(Wall wall) {
        if (standing.remove(wall.id(), wall)) delete(WallFile.of(folder, wall.id()));
    }

    private void delete(File file) {
        if (file.exists() && !file.delete()) {
            Debug.of(plugin).warn("Could not delete " + file.getPath() + "; the next start will try that wall again.");
        }
    }

    /**
     * Puts the replaced blocks back where the wall's material still stands.
     *
     * @param inline write the chunks this thread owns now and skip the rest,
     *               for a stop that no task will outlive
     */
    private CompletableFuture<Boolean> restore(World world, List<WallFile.Placed> blocks, Material material,
                                               boolean inline) {
        AtomicBoolean all = new AtomicBoolean(true);
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (List<WallFile.Placed> chunk : byChunkPlaced(blocks).values()) {
            WallFile.Placed first = chunk.getFirst();
            Location at = new Location(world, first.x(), first.y(), first.z());
            Runnable restore = () -> {
                try {
                    for (WallFile.Placed placed : chunk) {
                        Block block = world.getBlockAt(placed.x(), placed.y(), placed.z());
                        if (block.getType() != material) continue;
                        try {
                            block.setBlockData(Bukkit.createBlockData(placed.previous()), false);
                        } catch (IllegalArgumentException unreadable) {
                            block.setType(Material.AIR, false);
                        }
                    }
                } catch (RuntimeException failure) {
                    all.set(false);
                    Debug.of(plugin).error("Part of a wall could not be taken down.", failure);
                }
            };
            if (inline) {
                if (tasks().isOwnedBy(at)) restore.run();
                else all.set(false);
                continue;
            }
            CompletableFuture<Void> done = new CompletableFuture<>();
            work.add(done);
            tasks().runAtLocation(at, () -> {
                try {
                    restore.run();
                } finally {
                    done.complete(null);
                }
            });
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).thenApply(ignored -> all.get());
    }

    private TaskScheduler tasks() {
        return Tasks.of(plugin);
    }

    private static Map<Long, List<BlockVector>> byChunk(Collection<? extends BlockVector> blocks) {
        Map<Long, List<BlockVector>> chunks = new LinkedHashMap<>();
        for (BlockVector position : blocks) {
            chunks.computeIfAbsent(chunkKey(position.getBlockX(), position.getBlockZ()), key -> new ArrayList<>())
                    .add(position);
        }
        return chunks;
    }

    private static Map<Long, List<WallFile.Placed>> byChunkPlaced(List<WallFile.Placed> blocks) {
        Map<Long, List<WallFile.Placed>> chunks = new LinkedHashMap<>();
        for (WallFile.Placed placed : blocks) {
            chunks.computeIfAbsent(chunkKey(placed.x(), placed.z()), key -> new ArrayList<>()).add(placed);
        }
        return chunks;
    }

    private static long chunkKey(int x, int z) {
        return ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
    }
}
