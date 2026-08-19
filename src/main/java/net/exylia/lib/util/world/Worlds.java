package net.exylia.lib.util.world;

import net.exylia.lib.util.world.internal.WorldsBackend;
import net.exylia.lib.util.world.internal.WorldsBackendDetector;
import net.kyori.adventure.key.Key;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Entry point of the world module: creating and deleting worlds through the
 * Worlds plugin (net.thenextlvl.worlds).
 *
 * <pre>{@code
 * if (Worlds.isAvailable()) {
 *     Worlds.create(Key.key("myplugin", "arena"), "arena_1").thenAccept(world -> {
 *         if (world == null) {
 *             return; // the plugin declined; fall back to a vanilla path
 *         }
 *         // the world is loaded and ready
 *     });
 * }
 * }</pre>
 *
 * <h2>Why not {@code WorldCreator}</h2>
 * On Folia, Bukkit's own {@link org.bukkit.WorldCreator} cannot be driven safely
 * from an arbitrary thread. Worlds can, so a plugin that has to build a world at
 * runtime asks here and stops caring which platform it is on.
 *
 * <h2>Which Worlds</h2>
 * Worlds ships one API generation per Minecraft line, and the generations are
 * neither source- nor binary-compatible with each other:
 *
 * <table border="1">
 *   <caption>Supported generations</caption>
 *   <tr><th>Worlds</th><th>Minecraft</th><th>API root</th><th>Notes</th></tr>
 *   <tr><td>3.12.x</td><td>1.21.4 – 1.21.11</td><td>{@code net.thenextlvl.worlds.api}</td>
 *       <td>Java 21 bytecode; {@code WorldsProvider} service</td></tr>
 *   <tr><td>4.0.0</td><td>26.1.2</td><td>{@code net.thenextlvl.worlds}</td>
 *       <td>Java 25 bytecode; no {@code legacyName}</td></tr>
 *   <tr><td>4.1.0 – 4.4.0</td><td>26.1.2 / 26.2</td><td>{@code net.thenextlvl.worlds}</td>
 *       <td>Java 25 bytecode; full support</td></tr>
 * </table>
 *
 * <p>4.x is published as Java 25 bytecode while this library targets Java 21, so
 * it cannot go on the compile classpath at all. Both generations are bound
 * reflectively instead, and the right one is probed once at first use: the
 * backend that binds every member it needs wins. A Worlds release that renamed a
 * method is turned away during that probe rather than blowing up mid-creation,
 * and the caller simply sees no backend.
 *
 * <h2>What happens without Worlds</h2>
 * Nothing throws. {@link #isAvailable()} is {@code false},
 * {@link #backendName()} is {@code "none"}, {@link #create} completes with
 * {@code null} and {@link #delete} with {@code false}, so a caller can fall back
 * to a vanilla Bukkit path. Ask {@link #isAvailable()} up front to choose that
 * path rather than reading a {@code null} as an answer: it tells "Worlds is not
 * usable here" apart from "Worlds tried and refused this particular world".
 *
 * <h2>Threading</h2>
 * Every method here is safe from any thread, and none of them blocks. The
 * returned futures complete on whichever thread the Worlds plugin finishes on,
 * so a caller that then touches the game hops back through
 * {@code Tasks.of(plugin).runAtLocation(...)} first.
 *
 * @since 1.36.0
 */
public final class Worlds {

    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");

    private Worlds() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns whether a compatible Worlds version is installed and bound.
     *
     * <p>The first call probes; later ones read the remembered answer, negative
     * answers included.
     *
     * @return {@code true} when world operations will go through the plugin
     */
    public static boolean isAvailable() {
        return WorldsBackendDetector.backend() != null;
    }

    /**
     * Returns the bound API generation, for diagnostics.
     *
     * @return {@code "Worlds 4.x"}, {@code "Worlds 4.0.x"}, {@code "Worlds 3.x"},
     *         or {@code "none"} when nothing is bound
     */
    public static @NotNull String backendName() {
        WorldsBackend backend = WorldsBackendDetector.backend();
        return backend != null ? backend.name() : "none";
    }

    /**
     * Creates a world under {@code name}, with no terrain and no structures.
     *
     * <p>The same as {@code create(key, name, true)}.
     *
     * @param key  the namespaced key identifying the level, e.g.
     *             {@code myplugin:autoworld}
     * @param name the legacy world folder and name to expose the level under
     * @return a future completing with the world, or with {@code null} when no
     *         compatible Worlds is installed or the creation failed; the failure
     *         is logged rather than thrown
     */
    public static @NotNull CompletableFuture<World> create(@NotNull Key key, @NotNull String name) {
        return create(key, name, true);
    }

    /**
     * Creates a world under {@code name}.
     *
     * @param key       the namespaced key identifying the level, e.g.
     *                  {@code myplugin:autoworld}
     * @param name      the legacy world folder and name to expose the level under
     * @param voidWorld whether to force the void preset (no terrain, no
     *                  structures). Pass {@code false} to apply your own chunk
     *                  generator or biome provider after creation instead.
     * @return a future completing with the world, or with {@code null} when no
     *         compatible Worlds is installed or the creation failed; the failure
     *         is logged rather than thrown
     */
    public static @NotNull CompletableFuture<World> create(@NotNull Key key,
                                                           @NotNull String name,
                                                           boolean voidWorld) {
        WorldsBackend backend = WorldsBackendDetector.backend();
        if (backend == null) {
            return CompletableFuture.completedFuture(null);
        }
        return backend.createWorld(key, name, voidWorld)
                .exceptionally(t -> {
                    LOGGER.warning("[Worlds] " + backend.name() + " failed to create '"
                            + name + "': " + rootCause(t));
                    return null;
                });
    }

    /**
     * Deletes a world.
     *
     * @param world the world to delete
     * @return a future completing with {@code true} on success, or with
     *         {@code false} when no compatible Worlds is installed or the
     *         deletion failed; the failure is logged rather than thrown
     */
    public static @NotNull CompletableFuture<Boolean> delete(@NotNull World world) {
        WorldsBackend backend = WorldsBackendDetector.backend();
        if (backend == null) {
            return CompletableFuture.completedFuture(false);
        }
        return backend.deleteWorld(world)
                .exceptionally(t -> {
                    LOGGER.warning("[Worlds] " + backend.name() + " failed to delete '"
                            + world.getName() + "': " + rootCause(t));
                    return false;
                });
    }

    /**
     * Unwraps the future's own wrapper, so the logged line names the real
     * failure rather than a generic {@code CompletionException}.
     */
    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
