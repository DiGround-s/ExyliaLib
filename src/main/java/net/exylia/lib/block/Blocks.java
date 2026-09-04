package net.exylia.lib.block;

import net.exylia.lib.block.internal.BlockRuntime;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks in the world that answer clicks.
 *
 * <pre>{@code
 * PluginBlocks blocks = Blocks.of(this);
 *
 * blocks.at(crate.location())
 *         .onRight(click -> open(click.player(), crate))
 *         .onLeft(click -> preview(click.player(), crate))
 *         .register();
 * }</pre>
 *
 * <h2>What it is for</h2>
 * A crate, a shop, a warp pad, a quest board: a block a plugin placed that is
 * not what its material says it is. Every plugin that has wanted one wrote the
 * same three things — an interact listener that finds the block by its
 * coordinates, a break listener so nobody takes it away, and a guard so a
 * barrel does not open its own inventory.
 *
 * <h2>What it does not do</h2>
 * <b>It does not remember anything across restarts.</b> Where a plugin's blocks
 * are is that plugin's data, in its own table next to what each block means, and
 * a registry that stored locations would only ever hold half of a row. Register
 * them again when the plugin comes up, from whatever it already loads.
 *
 * <h2>Contracts</h2>
 * <ul>
 *   <li><b>One block, one registration.</b> Registering over an existing one
 *       replaces it, whoever owned it.</li>
 *   <li><b>Handlers run on the thread that owns the block</b>, so the world can
 *       be read from them on Folia as well as Bukkit.</li>
 *   <li><b>A repeated click is one click.</b> A held left button fires every
 *       tick and both hands fire a right click; within 250ms of the same button
 *       on the same block, the handler is not called again.</li>
 *   <li><b>A handler that throws costs its own click</b> — it is reported
 *       against its plugin and the event carries on.</li>
 *   <li><b>Nothing survives its plugin.</b> Disabling it unregisters every
 *       block it owns; the blocks themselves are left standing.</li>
 * </ul>
 *
 * @since 1.110.0
 */
public final class Blocks {

    private static final Map<String, PluginBlocks> BY_PLUGIN = new ConcurrentHashMap<>();

    private Blocks() {
        throw new AssertionError("No instances.");
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginBlocks of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginBlocks(plugin));
    }

    /**
     * What is registered at a location, whoever owns it.
     *
     * <p>Worth asking before anything that would fight it: a protection check,
     * a build tool, a region that clears blocks.
     *
     * @param location where to look
     * @return the registration, or {@code null}
     */
    public static @Nullable ClickableBlock at(@NotNull Location location) {
        return BlockRuntime.at(location);
    }

    /** Whether a block is registered by any plugin. */
    public static boolean isRegistered(@NotNull Block block) {
        return BlockRuntime.at(block) != null;
    }

    /** How many are registered across every plugin. */
    public static int active() {
        return BlockRuntime.count();
    }

    /**
     * Forgets one plugin's blocks. Called by the library when it is disabled.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        BlockRuntime.releaseOwner(pluginName);
        BY_PLUGIN.remove(pluginName);
    }

    /** Forgets every block on the server, on shutdown. */
    public static void releaseAll() {
        BlockRuntime.releaseAll();
        BY_PLUGIN.clear();
    }
}
