package net.exylia.lib.util.world.internal;

import net.kyori.adventure.key.Key;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * Version-specific adapter over the Worlds plugin (net.thenextlvl.worlds).
 *
 * <p>Each implementation binds reflectively against exactly one API generation
 * and resolves every member it needs once, while it is being constructed. A
 * backend that cannot resolve a mandatory member refuses to construct, so a
 * backend that exists is always fully usable: the caller never discovers a
 * missing method halfway through building a world.
 *
 * <p>Implementations are immutable and safe from any thread.
 */
public interface WorldsBackend {

    /**
     * Returns a short human-readable name of the bound API generation, such as
     * {@code "Worlds 4.x"}. Diagnostics only.
     *
     * @return the backend name
     */
    String name();

    /**
     * Creates a level through the Worlds plugin.
     *
     * @param key        the namespaced key identifying the level
     * @param legacyName the legacy world folder/name to expose the level under
     * @param voidWorld  whether to force a structure-less void preset. When
     *                   {@code false} the caller is expected to apply its own
     *                   chunk generator or biome provider after creation.
     * @return a future, never {@code null} itself; it may complete with
     *         {@code null} when the plugin declined the creation
     */
    CompletableFuture<World> createWorld(Key key, String legacyName, boolean voidWorld);

    /**
     * Deletes a world through the Worlds plugin.
     *
     * @param world the world to delete
     * @return a future completing with {@code true} on success
     */
    CompletableFuture<Boolean> deleteWorld(World world);
}
