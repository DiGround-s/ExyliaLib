package net.exylia.lib.util.world;

import net.exylia.lib.platform.Platform;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.world.internal.PlotGrid;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The one throwaway world every Exylia plugin pastes its arena copies into.
 *
 * <pre>{@code
 * TemporaryWorld.reserve(width, depth).thenAccept(plot -> {
 *     if (plot == null) {
 *         return; // no world on this server: fall back to the original arena
 *     }
 *     schematics.paste(name, plot.origin(64));
 * });
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * The world is never kept. Its folder is deleted when the library enables —
 * before any world loads, so a copy left by a crash is gone too — and again
 * when the library disables. It is created on the first {@link #world()} or
 * {@link #reserve} call, not at startup, so a server whose plugins never ask
 * for it pays nothing. A plugin that wants its first match to start without
 * waiting calls {@link #world()} from its {@code onEnable}.
 *
 * <h2>Where a plot lands</h2>
 * Every plot starts on a chunk boundary, and the empty space to its neighbours
 * is wider than the server's view distance: a player standing on the edge of
 * one copy never sees another. A plot is never placed
 * where another one has been during this run, so it always lands on untouched
 * void: there is nothing to clear first, and nothing to give back afterwards.
 *
 * <h2>Rules</h2>
 * Void, no structures, hard difficulty, PvP on, frozen at noon, no weather,
 * mobs, fire spread, mob griefing, random ticks, phantoms, advancement or death
 * announcements, and no autosave.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread and none blocks. The futures complete on
 * whichever thread created the world, so hop back through
 * {@code Tasks.of(plugin).runAtLocation(...)} before touching the game.
 *
 * @since 1.229.0
 */
public final class TemporaryWorld {

    /** The world's name and folder. */
    public static final String NAME = "exylialib_temporary";

    private static final Key KEY = Key.key("exylialib", "temporary");
    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");
    private static PlotGrid grid;

    private static volatile Plugin library;
    private static volatile CompletableFuture<Void> wiped = CompletableFuture.completedFuture(null);
    private static CompletableFuture<World> creation;

    private TemporaryWorld() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns the world, creating it on the first call.
     *
     * @return a future completing with the world, or with {@code null} when it
     *         could not be created on this server; a later call tries again
     */
    public static synchronized @NotNull CompletableFuture<World> world() {
        if (creation == null || (creation.isDone() && creation.getNow(null) == null)) {
            creation = wiped.thenCompose(ignored -> create());
        }
        return creation;
    }

    /**
     * Returns the world if it is already loaded.
     *
     * @return the world, or {@code null} while it has not been created yet
     */
    public static @Nullable World loaded() {
        return Bukkit.getWorld(NAME);
    }

    /**
     * Reserves an empty part of the world for one arena copy.
     *
     * @param sizeX the room needed along X, in blocks
     * @param sizeZ the room needed along Z, in blocks
     * @return a future completing with the plot, or with {@code null} when the
     *         world could not be created
     */
    public static @NotNull CompletableFuture<Plot> reserve(int sizeX, int sizeZ) {
        if (sizeX < 1 || sizeZ < 1) {
            throw new IllegalArgumentException("A plot needs a positive size, got " + sizeX + "x" + sizeZ);
        }
        return world().thenApply(world -> {
            if (world == null) {
                return null;
            }
            int[] corner = grid().reserve(sizeX, sizeZ);
            return new Plot(world, corner[0], corner[1], sizeX, sizeZ);
        });
    }

    /**
     * A reserved part of the temporary world.
     *
     * @param world the temporary world
     * @param minX  the lowest block X of the plot
     * @param minZ  the lowest block Z of the plot
     * @param sizeX the plot's size along X, in blocks
     * @param sizeZ the plot's size along Z, in blocks
     */
    public record Plot(@NotNull World world, int minX, int minZ, int sizeX, int sizeZ) {

        /**
         * Returns the plot's minimum corner at a height.
         *
         * @param y the height, usually where the copy is pasted
         * @return a new location
         */
        public @NotNull Location origin(double y) {
            return new Location(world, minX, y, minZ);
        }
    }

    /**
     * Deletes whatever a previous run left. Called once by the library at enable,
     * which loads before the worlds do.
     *
     * @param plugin the library
     */
    public static void start(@NotNull Plugin plugin) {
        library = plugin;
        Path folder = folder();
        if (Bukkit.getWorld(NAME) == null && Files.exists(folder)) {
            wiped = CompletableFuture.runAsync(() -> deleteFolder(folder));
        }
    }

    /**
     * Takes everybody out and deletes the world. Called once by the library at
     * disable; whatever this cannot finish, the next {@link #start} does.
     */
    public static synchronized void shutdown() {
        creation = null;
        grid = null;
        World world = Bukkit.getWorld(NAME);
        if (world == null) {
            deleteFolder(folder());
            return;
        }
        if (Platform.isFolia()) {
            // Folia cannot unload a world itself; the next start removes the folder
            // if Worlds does not get to it first.
            Worlds.delete(world);
            return;
        }
        World fallback = Bukkit.getWorlds().get(0);
        for (Player player : new ArrayList<>(world.getPlayers())) {
            player.teleport(fallback.getSpawnLocation());
        }
        // Inline: the server is stopping and a background delete would be killed.
        if (Bukkit.unloadWorld(world, false)) {
            deleteFolder(folder());
        }
    }

    private static CompletableFuture<World> create() {
        World leftover = Bukkit.getWorld(NAME);
        if (leftover != null) {
            // Loaded by someone else before we asked: never reuse what it holds.
            CompletableFuture<Boolean> unloaded = Platform.isFolia()
                    ? Worlds.delete(leftover)
                    : onServerThread(() -> Bukkit.unloadWorld(leftover, false));
            return unloaded.thenCompose(deleted -> {
                if (!Boolean.TRUE.equals(deleted)) {
                    LOGGER.warning("[TemporaryWorld] '" + NAME + "' was already loaded and could not be deleted");
                    return CompletableFuture.completedFuture(null);
                }
                deleteFolder(folder());
                return create();
            });
        }
        if (Platform.isFolia()) {
            return Worlds.create(KEY, NAME).thenCompose(world -> {
                if (world == null) {
                    LOGGER.warning("[TemporaryWorld] Worlds could not create '" + NAME + "'");
                    return CompletableFuture.completedFuture(null);
                }
                return onServerThread(() -> applyRules(world));
            });
        }
        return onServerThread(() -> {
            World world = new WorldCreator(NAME)
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:the_void\"}")
                    .createWorld();
            if (world == null) {
                LOGGER.warning("[TemporaryWorld] Could not create '" + NAME + "'");
                return null;
            }
            return applyRules(world);
        });
    }

    private static World applyRules(World world) {
        world.setAutoSave(false);
        world.setDifficulty(Difficulty.HARD);
        world.setPVP(true);
        world.setSpawnFlags(false, false);
        world.setTime(6000);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        LOGGER.info("[TemporaryWorld] '" + NAME + "' ready");
        return world;
    }

    /**
     * The grid, sized on first use: the gap is the view distance plus a chunk,
     * and never under 64 blocks.
     */
    private static synchronized PlotGrid grid() {
        if (grid == null) {
            grid = new PlotGrid(Math.max(64, (Bukkit.getViewDistance() + 1) * 16));
        }
        return grid;
    }

    private static <T> CompletableFuture<T> onServerThread(java.util.function.Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin plugin = library;
        if (plugin == null) {
            future.complete(null);
            return future;
        }
        Tasks.of(plugin).run(() -> {
            try {
                future.complete(task.get());
            } catch (RuntimeException e) {
                LOGGER.warning("[TemporaryWorld] Could not set up '" + NAME + "': " + e);
                future.complete(null);
            }
        });
        return future;
    }

    private static Path folder() {
        return Bukkit.getWorldContainer().toPath().resolve(NAME);
    }

    private static void deleteFolder(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            LOGGER.warning("[TemporaryWorld] Could not delete " + folder + ": " + e);
        }
    }
}
