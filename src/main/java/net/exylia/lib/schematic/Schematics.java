package net.exylia.lib.schematic;

import net.exylia.lib.schematic.internal.SchematicRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point of the schematic module.
 *
 * <p>Saving a box of the world, pasting it back, and regenerating an arena
 * between matches.
 *
 * <pre>{@code
 * PluginSchematics schematics = Schematics.of(this);
 *
 * // Setting an arena up, once.
 * schematics.save("arena_1", bounds, world);
 *
 * // Between matches.
 * schematics.regenerate("arena_1", bounds, world, RegenerateOptions.defaults())
 *         .thenAccept(result -> { if (result.isSuccess()) reopen(); });
 *
 * // Drawing the arena list: free, no disk.
 * boolean ready = schematics.exists("arena_1");
 * }</pre>
 *
 * <h2>Two engines, one format</h2>
 * FastAsyncWorldEdit when it is installed and enabled; otherwise a built-in
 * engine that reads and writes the same Sponge {@code .schem} files with the
 * Paper API alone, for servers where FAWE cannot run (NeoForge or Forge hybrids
 * such as Youer or Mohist). An arena saved by either is pasted by the other.
 * The built-in engine keeps container contents, sign text and spawner types,
 * but not the rest of a block entity's data; {@code docs/schematics.md} lists
 * exactly what it keeps.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread and none of them blocks. Files and
 * clipboards are read off the server threads; entities in a box are read on the
 * thread that owns the location; a trapped player is moved on the thread that
 * owns them. Futures complete on whichever thread finished the work, so a
 * caller that then touches the game hops back through
 * {@code Tasks.of(plugin).runAtLocation(...)} first.
 *
 * @since 1.48.0
 */
public final class Schematics {

    private Schematics() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns the schematics of a plugin.
     *
     * <p>Files are written under the plugin's own data folder, and everything it
     * still has in flight completes as {@link SchematicOutcome#FAILED} when it
     * is disabled rather than leaving a promise its own scheduler can no longer
     * keep.
     *
     * @param plugin the owning plugin
     * @return its schematics
     */
    public static @NotNull PluginSchematics of(@NotNull Plugin plugin) {
        // Starts the folder listing now rather than on the first exists(), so a
        // plugin that asks for this in onEnable has an answered index by the
        // time a player opens the menu that reads it.
        SchematicRuntime.prepare(plugin);
        return new PluginSchematics(plugin);
    }

    /**
     * Returns whether anything can be saved or pasted at all.
     *
     * <p>{@code true} whenever the library is running: without
     * FastAsyncWorldEdit the built-in engine is bound instead. {@code false}
     * only before ExyliaLib enables or after it stops, and then every call
     * still works and completes with {@link SchematicOutcome#UNSUPPORTED}.
     *
     * @return {@code true} when an engine is bound
     */
    public static boolean isSupported() {
        return SchematicRuntime.isSupported();
    }

    /**
     * Returns which engine is bound, or why {@link #isSupported()} is
     * {@code false}, as a sentence to show an admin.
     *
     * <p>Printed once at startup rather than once per refused call, because it
     * is a fact about the server rather than about the request.
     *
     * @return the reason, or a sentence naming the bound engine
     */
    public static @NotNull String unsupportedReason() {
        return SchematicRuntime.unsupportedReason();
    }
}
