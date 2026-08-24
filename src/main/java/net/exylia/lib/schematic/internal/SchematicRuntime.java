package net.exylia.lib.schematic.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.schematic.RegenerateOptions;
import net.exylia.lib.schematic.SchematicResult;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The schematic module's working parts.
 *
 * <p>Everything that decides anything lives here; the only thing that knows how
 * to move blocks lives behind {@link SchematicEngine}. That split is what lets
 * the name checking, the folder resolution, the order of the stages and the
 * behaviour of a stage that fails all be tested with no FastAsyncWorldEdit and
 * no server.
 *
 * <h2>Every future completes</h2>
 * No chain here can hang. Every stage runs inside {@link #stage} which guards
 * {@link Throwable} rather than {@link Exception}: a FAWE version whose API
 * moved throws {@link NoClassDefFoundError}, and an unseen failure is a future
 * nobody completes. That is the ExyliaCommons bug this fixes most directly — it
 * chained {@code getChunkAtAsync().thenAccept(...)} with no {@code exceptionally},
 * so one chunk that failed to load left the caller waiting forever.
 */
@ApiStatus.Internal
public final class SchematicRuntime {

    private static final Map<String, Owner> OWNERS = new ConcurrentHashMap<>();

    private static volatile Plugin library;

    private SchematicRuntime() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Binds an engine, if the server has one. Called once, by the library.
     *
     * @param plugin the library plugin
     */
    public static void init(@NotNull Plugin plugin) {
        library = plugin;
        Engines.bind();
        if (!Engines.isSupported()) {
            // Once, at startup, rather than once per refused call: this is a
            // fact about the server rather than about any one request.
            Debug.of(plugin).warn(Engines.reason());
        }
    }

    /** Whether anything can be saved or pasted. */
    public static boolean isSupported() {
        return Engines.isSupported();
    }

    /** Why not, as a sentence to show an admin. */
    public static @NotNull String unsupportedReason() {
        return Engines.reason();
    }

    /**
     * Makes sure a plugin has its folders and starts its first listing.
     *
     * <p>Idempotent: everything a plugin has is keyed by its name here, so the
     * facade handed out is a thin reference and asking for a second one costs
     * nothing.
     *
     * @param plugin the owner
     */
    public static void prepare(@NotNull Plugin plugin) {
        owner(plugin);
    }

    /**
     * Gives up everything a plugin still has.
     *
     * <p>Its outstanding futures complete as {@code FAILED} naming the
     * schematic, rather than leaving a promise its own scheduler can no longer
     * keep — this runs before the task module releases that scheduler.
     *
     * @param pluginName the plugin going away
     */
    public static void release(@NotNull String pluginName) {
        Owner owner = OWNERS.remove(pluginName);
        if (owner != null) {
            owner.abandon();
        }
    }

    /** Releases every plugin and drops whatever the engine holds. */
    public static void shutdown() {
        for (String pluginName : Set.copyOf(OWNERS.keySet())) {
            release(pluginName);
        }
        OWNERS.clear();
        Engines.unbind();
        library = null;
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /**
     * Writes a box of the world to a file.
     *
     * @param plugin       the owner
     * @param name         the schematic name
     * @param bounds       the box
     * @param world        the world the box is in
     * @param copyEntities whether loose entities are part of the copy
     * @return how it ended
     */
    public static @NotNull CompletableFuture<SchematicResult> save(
            @NotNull Plugin plugin, @NotNull String name, @NotNull Cuboid bounds,
            @NotNull WorldIdentity world, boolean copyEntities) {

        Owner owner = owner(plugin);
        CompletableFuture<SchematicResult> future = owner.begin(name);
        String refusal = SchematicNames.reasonToRefuse(name);
        if (refusal != null) {
            return owner.finish(future, SchematicResult.failed(String.valueOf(name), refusal));
        }
        if (!Engines.isSupported()) {
            return owner.finish(future, SchematicResult.unsupported(name, Engines.reason()));
        }
        World target = resolve(world);
        if (target == null) {
            return owner.finish(future,
                    SchematicResult.failed(name, "world " + world.fallbackName() + " is not loaded"));
        }
        Bounds box = Bounds.of(bounds);
        owner.scheduler().runAsync(() -> stage(owner, future, name, () -> {
            File destination = owner.store.destination(name);
            engine().save(target, box, destination, copyEntities);
            owner.store.remember(name);
            owner.finish(future, SchematicResult.success(name));
        }));
        return future;
    }

    /**
     * Reads a file and puts its blocks into the world.
     *
     * @param plugin       the owner
     * @param name         the schematic name
     * @param at           where the schematic's origin lands
     * @param copyEntities whether loose entities in the file are pasted
     * @return how it ended
     */
    public static @NotNull CompletableFuture<SchematicResult> paste(
            @NotNull Plugin plugin, @NotNull String name, @NotNull Location at,
            boolean copyEntities) {

        Owner owner = owner(plugin);
        CompletableFuture<SchematicResult> future = owner.begin(name);
        String refusal = SchematicNames.reasonToRefuse(name);
        if (refusal != null) {
            return owner.finish(future, SchematicResult.failed(String.valueOf(name), refusal));
        }
        if (!Engines.isSupported()) {
            return owner.finish(future, SchematicResult.unsupported(name, Engines.reason()));
        }
        World target = at.getWorld();
        if (target == null) {
            return owner.finish(future, SchematicResult.failed(name, "the location has no world"));
        }
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        owner.scheduler().runAsync(() -> stage(owner, future, name, () -> {
            File source = owner.store.find(name);
            if (source == null) {
                owner.finish(future, SchematicResult.notFound(name));
                return;
            }
            engine().paste(target, x, y, z, source, copyEntities);
            owner.finish(future, SchematicResult.success(name));
        }));
        return future;
    }

    /**
     * Puts an arena back the way it was saved.
     *
     * <p>Three stages, in this order and no other: clear the loose entities
     * while the old blocks are still there, paste the blocks back, then move
     * anyone the new blocks buried. Rescuing first would put a player back
     * inside a wall that had not been placed yet.
     *
     * <p>The future completes once the blocks are back, which is what a caller
     * reopening the arena is waiting for. The rescue then follows on the world
     * thread: making the reopen wait on a player teleport buys nothing, and a
     * rescue that finds nobody must not turn a finished regeneration into a
     * failure.
     *
     * @param plugin  the owner
     * @param name    the schematic name
     * @param bounds  the box the arena occupies
     * @param world   the world it is in
     * @param options which of the two side stages run
     * @return how it ended
     */
    public static @NotNull CompletableFuture<SchematicResult> regenerate(
            @NotNull Plugin plugin, @NotNull String name, @NotNull Cuboid bounds,
            @NotNull WorldIdentity world, @NotNull RegenerateOptions options) {

        Owner owner = owner(plugin);
        CompletableFuture<SchematicResult> future = owner.begin(name);
        String refusal = SchematicNames.reasonToRefuse(name);
        if (refusal != null) {
            return owner.finish(future, SchematicResult.failed(String.valueOf(name), refusal));
        }
        if (!Engines.isSupported()) {
            return owner.finish(future, SchematicResult.unsupported(name, Engines.reason()));
        }
        World target = resolve(world);
        if (target == null) {
            return owner.finish(future,
                    SchematicResult.failed(name, "world " + world.fallbackName() + " is not loaded"));
        }
        Bounds box = Bounds.of(bounds);
        Location origin = centre(target, box);

        owner.scheduler().runAsync(() -> stage(owner, future, name, () -> {
            // Resolved before anything destructive happens: a regeneration of a
            // schematic that is not there must not first kill the arena's
            // entities and then report NOT_FOUND.
            File source = owner.store.find(name);
            if (source == null) {
                owner.finish(future, SchematicResult.notFound(name));
                return;
            }
            if (options.clearEntities()) {
                owner.scheduler().runAtLocation(origin, () -> stage(owner, future, name, () -> {
                    clear(target, box);
                    owner.scheduler().runAsync(() -> stage(owner, future, name,
                            () -> pasteBack(owner, future, name, target, box, source, origin, options)));
                }));
                return;
            }
            pasteBack(owner, future, name, target, box, source, origin, options);
        }));
        return future;
    }

    /**
     * Deletes a schematic from both folders.
     *
     * @param plugin the owner
     * @param name   the schematic name
     * @return whether anything was removed
     */
    public static @NotNull CompletableFuture<Boolean> delete(@NotNull Plugin plugin,
                                                             @NotNull String name) {
        Owner owner = owner(plugin);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!SchematicNames.isValid(name)) {
            future.complete(false);
            return future;
        }
        owner.scheduler().runAsync(() -> {
            boolean removed;
            try {
                removed = owner.store.delete(name);
            } catch (Throwable failure) {
                owner.debug.warn("Could not delete the schematic '" + name + "': " + failure);
                removed = false;
            }
            // Forgotten either way: a file the module could not remove is one
            // it should stop promising, and the next restart re-reads the disk.
            owner.store.forget(name);
            future.complete(removed);
        });
        return future;
    }

    /** Whether a name is known, from memory. Never touches the disk. */
    public static boolean exists(@NotNull Plugin plugin, @NotNull String name) {
        return owner(plugin).store.contains(name);
    }

    /** Whether the first listing has finished. */
    public static boolean isIndexed(@NotNull Plugin plugin) {
        return owner(plugin).store.isIndexed();
    }

    /** Every name known, as a snapshot. */
    public static @NotNull Set<String> names(@NotNull Plugin plugin) {
        return owner(plugin).store.names();
    }

    /** The folder this plugin's schematics are written to. */
    public static @NotNull File folder(@NotNull Plugin plugin) {
        return owner(plugin).store.folder();
    }

    // ------------------------------------------------------------------
    // Stages
    // ------------------------------------------------------------------

    /**
     * Pastes the blocks back, completes the caller, and schedules the rescue.
     *
     * <p>Runs off the server threads, which is why the rescue below is
     * <em>scheduled</em> rather than called: it reads the world's players and
     * their locations, and a world read from an async thread is a race on
     * Bukkit and a hard failure on Folia.
     */
    private static void pasteBack(Owner owner, CompletableFuture<SchematicResult> future,
                                  String name, World target, Bounds box, File source,
                                  Location origin, RegenerateOptions options) throws Exception {
        engine().paste(target, box.minX(), box.minY(), box.minZ(), source, false);
        if (options.moveTrappedPlayers()) {
            owner.scheduler().runAtLocation(origin, () -> rescue(owner, target, box));
        }
        // Queued before the caller is told, never after. Completing first lets
        // whoever was waiting on the regeneration run before the rescue is even
        // scheduled, so an arena can reopen while somebody is still in a wall.
        owner.finish(future, SchematicResult.success(name));
    }

    /**
     * Removes the loose entities inside the box.
     *
     * <p>Players are never touched: a regeneration clears what the last match
     * dropped, not who is watching it.
     */
    private static void clear(World world, Bounds box) {
        BoundingBox area = new BoundingBox(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0);
        for (Entity entity : world.getNearbyEntities(area)) {
            if (entity instanceof Player) {
                continue;
            }
            entity.remove();
        }
    }

    /**
     * Moves anyone the new blocks buried up to the nearest air.
     *
     * <p>Runs on the thread that owns the box, because reading the world's
     * players is a world read. Each move then hops again onto the thread that
     * owns the player, because moving a player is not a world write.
     */
    private static void rescue(Owner owner, World world, Bounds box) {
        List<Player> players;
        try {
            players = world.getPlayers();
        } catch (Throwable failure) {
            owner.debug.warn("Could not read who was inside a regenerated area: " + failure);
            return;
        }
        for (Player player : players) {
            Location where = player.getLocation();
            if (where == null || !box.contains(where.getBlockX(), where.getBlockY(),
                    where.getBlockZ())) {
                continue;
            }
            Location safe = firstAirAbove(world, box, where);
            if (safe == null) {
                continue;
            }
            owner.scheduler().runAtEntity(player, () -> player.teleport(safe));
        }
    }

    /**
     * The lowest standing spot at or above a player, or {@code null} when they
     * are not buried.
     *
     * <p>Emptiness is asked of the {@code Block}, never of the {@code Material}:
     * {@code Material.isAir()} resolves against {@code org.bukkit.Registry} and
     * throws {@link ExceptionInInitializerError} without a live server, which
     * would make this untestable.
     */
    private static @Nullable Location firstAirAbove(World world, Bounds box, Location where) {
        int x = where.getBlockX();
        int z = where.getBlockZ();
        int from = where.getBlockY();
        // One block past the top: somebody buried by the ceiling is put on it
        // rather than left inside it.
        int to = box.maxY() + 2;
        for (int y = from; y <= to; y++) {
            if (world.getBlockAt(x, y, z).isEmpty() && world.getBlockAt(x, y + 1, z).isEmpty()) {
                return y == from
                        // Already standing in air: not buried, nothing to do.
                        ? null
                        : new Location(world, x + 0.5, y, z + 0.5,
                                where.getYaw(), where.getPitch());
            }
        }
        return null;
    }

    /**
     * Runs one stage of a chain, and never lets it escape.
     *
     * <p>{@link Throwable} rather than {@link Exception} on purpose: a FAWE
     * version whose API moved throws {@link NoClassDefFoundError}, and an
     * unseen failure here is a future nobody completes.
     */
    private static void stage(Owner owner, CompletableFuture<SchematicResult> future,
                              String name, Stage body) {
        try {
            body.run();
        } catch (Throwable failure) {
            owner.debug.warn("The schematic '" + name + "' could not be handled: " + failure);
            owner.finish(future, SchematicResult.failed(name, String.valueOf(failure)));
        }
    }

    /** One step of a chain, allowed to fail in any way. */
    @FunctionalInterface
    private interface Stage {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private static SchematicEngine engine() {
        SchematicEngine bound = Engines.engine();
        if (bound == null) {
            // Only reachable if FAWE went away between the check and the stage.
            throw new IllegalStateException(Engines.reason());
        }
        return bound;
    }

    /**
     * The world a portable identity names.
     *
     * <p>By id first, then by the fallback name: the id is authoritative, and
     * the name is what survives a world that was unloaded and loaded again.
     */
    private static @Nullable World resolve(WorldIdentity world) {
        World byId = Bukkit.getWorld(world.id());
        return byId != null ? byId : Bukkit.getWorld(world.fallbackName());
    }

    private static Location centre(World world, Bounds box) {
        return new Location(world, box.centreX(), box.centreY(), box.centreZ());
    }

    private static Owner owner(Plugin plugin) {
        return OWNERS.computeIfAbsent(plugin.getName(), ignored -> new Owner(plugin));
    }

    /** Everything one plugin has: its folders, its index, and what is in flight. */
    private static final class Owner {

        private final Plugin plugin;
        private final Debug debug;
        private final SchematicStore store;
        private final Set<Pending> pending = ConcurrentHashMap.newKeySet();

        private Owner(Plugin plugin) {
            this.plugin = plugin;
            this.debug = Debug.of(plugin);
            this.store = new SchematicStore(plugin.getDataFolder());
            // Listing a directory is I/O, and a server with a hundred arenas
            // should not pay for it on a tick. Until this finishes exists()
            // answers false for everything, which costs nothing real: an
            // operation asked for anyway reads the disk and answers honestly.
            scheduler().runAsync(() -> {
                try {
                    store.seed();
                } catch (Throwable failure) {
                    debug.warn("Could not list the schematics folder: " + failure);
                }
            });
        }

        private TaskScheduler scheduler() {
            return Tasks.of(plugin);
        }

        private CompletableFuture<SchematicResult> begin(String name) {
            CompletableFuture<SchematicResult> future = new CompletableFuture<>();
            pending.add(new Pending(future, name));
            return future;
        }

        /**
         * Completes a future once, whichever stage got there first.
         *
         * <p>A regeneration completes on its paste stage and then keeps going,
         * so a rescue that fails must not try to complete it a second time.
         */
        private CompletableFuture<SchematicResult> finish(
                CompletableFuture<SchematicResult> future, SchematicResult result) {
            pending.removeIf(entry -> entry.future() == future);
            future.complete(result);
            return future;
        }

        /** Completes everything still in flight, naming each schematic. */
        private void abandon() {
            for (Pending entry : Set.copyOf(pending)) {
                entry.future().complete(SchematicResult.failed(entry.name(),
                        "the owning plugin was disabled"));
            }
            pending.clear();
        }
    }

    /** A future that has not been answered yet, and what it is about. */
    private record Pending(CompletableFuture<SchematicResult> future, String name) {
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /** Forgets every plugin, without touching the engine. For tests. */
    static void forgetOwnersForTests() {
        OWNERS.clear();
    }
}
