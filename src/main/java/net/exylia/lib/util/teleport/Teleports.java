package net.exylia.lib.util.teleport;

import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moving a player, with everything that has to happen around it.
 *
 * <pre>{@code
 * PluginTeleports teleports = Teleports.of(this);
 *
 * teleports.to(player, warp)
 *         .warmup(3.0)
 *         .cooldown("warp", 30.0)
 *         .safe()
 *         .start();
 * }</pre>
 *
 * <h2>What it does</h2>
 * Waits out a countdown that moving or being hit calls off, refuses the
 * teleport when a cooldown is still running, finds somewhere survivable to land,
 * announces the move so other plugins can veto it, and answers the caller with
 * exactly one result however it ended.
 *
 * <h2>Why this is not each plugin's own code</h2>
 * Every one of those parts is easy to write and easy to write subtly wrong, and
 * the wrong versions all look identical until a player finds them: a countdown
 * that keeps ticking after the player quit, a cooldown charged for a teleport
 * that was cancelled, a teleport performed off the owning thread that crashes a
 * Folia server, a destination inside a wall.
 *
 * <p>Written once, every plugin gets the version that handles them. Written
 * per plugin, the server gets one of each.
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li><b>One result, always.</b> Every request completes exactly once, and
 *       never exceptionally &mdash; including the ones that never move anybody.</li>
 *   <li><b>Nothing outlives its player.</b> Quitting, being kicked or the
 *       plugin being disabled ends the countdown and its timer.</li>
 *   <li><b>A cancelled teleport is free.</b> The cooldown it claimed is given
 *       back, because the player never received what they paid for.</li>
 *   <li><b>Other plugins get a say.</b> {@link ExyliaTeleportEvent} is fired
 *       for every move, on the thread owning the player.</li>
 *   <li><b>One countdown per player.</b> A second teleport cancels the first
 *       rather than dragging them through both.</li>
 * </ul>
 *
 * @since 1.34.0
 */
public final class Teleports {

    private static final Map<String, PluginTeleports> BY_PLUGIN = new ConcurrentHashMap<>();

    private Teleports() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginTeleports of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginTeleports(plugin));
    }

    /**
     * Whether this player is waiting out a countdown right now.
     *
     * <p>Worth asking before anything that would fight it: a combat check, a
     * menu that moves them, another teleport.
     *
     * @param player the player
     * @return whether they are counting down
     */
    public static boolean isWarmingUp(@NotNull Player player) {
        return TeleportRuntime.isWarmingUp(player.getUniqueId());
    }

    /** How many countdowns are running across every plugin. */
    public static int active() {
        return TeleportRuntime.active();
    }

    /**
     * Ends one plugin's countdowns and forgets it.
     *
     * <p>Called by the library when the plugin is disabled, before its
     * scheduler goes away: a countdown owns an entity timer belonging to it.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        TeleportRuntime.endAllOf(pluginName);
        BY_PLUGIN.remove(pluginName);
    }

    /** Ends every countdown on the server, on shutdown. */
    public static void releaseAll() {
        TeleportRuntime.endEverything();
        BY_PLUGIN.clear();
    }
}
