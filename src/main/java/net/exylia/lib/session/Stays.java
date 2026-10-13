package net.exylia.lib.session;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player kept somewhere a plugin owns, and given back exactly as they came.
 *
 * <pre>{@code
 * private PluginStays stays;
 *
 * @Override
 * public void onEnable() {
 *     stays = Stays.of(this);
 * }
 *
 * // Into the arena: claimed and saved here, emptied once they stand in it.
 * stays.enter(player, "duel", () -> { endMatch(player); return true; }).ifPresent(stay ->
 *         stay.ready().thenCompose(ignored -> teleport(player, arena))
 *                 .thenCompose(ignored -> stays.dress(player, GameMode.ADVENTURE, stay::isCurrent)));
 *
 * // Out of it: home, restored there, released last.
 * stays.leave(player);
 *
 * // On join: whatever a crash left behind goes back, and so does the player.
 * stays.recover(player);
 * }</pre>
 *
 * <h2>What it replaces</h2>
 * Every arena plugin wrote the same two hundred lines: claim the player through
 * {@link Sessions}, save a snapshot, teleport, empty them on arrival, and on the
 * way out send them home, restore them there and release the claim. Each copy
 * had to learn the same three orderings the hard way, and this class keeps them:
 * <ul>
 *   <li>The snapshot is taken before the teleport and the inventory is emptied
 *       only after it, because a per-world inventory plugin writes whatever the
 *       player holds into the world they leave.</li>
 *   <li>A restore never starts before its save has landed. The database runs
 *       writes concurrently, so a restore read ahead of the save finds nothing
 *       and the late save is restored again on the next join.</li>
 *   <li>The claim is released last. Releasing tells every other plugin the
 *       player is free, and nothing may dress them while their own things are
 *       still on the way back.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * One instance per plugin, the same every time. Nothing to close: the claims are
 * released by {@link Sessions} when the plugin is disabled, and the snapshots
 * are stored, so {@link PluginStays#recover} gives them back on the next join.
 *
 * <h2>Reload</h2>
 * Not applicable: nothing here is derived from the palette.
 *
 * @since 1.292.0
 */
public final class Stays {

    private static final Map<String, PluginStays> BY_PLUGIN = new ConcurrentHashMap<>();

    private Stays() {
    }

    /**
     * This plugin's stays.
     *
     * @param plugin the plugin
     * @return its view, the same instance for as long as this load of the plugin lasts
     */
    public static @NotNull PluginStays of(@NotNull Plugin plugin) {
        // A reload in place hands over a new Plugin object: the old view's
        // memory belongs to the load that is gone.
        return BY_PLUGIN.compute(plugin.getName(), (name, stays) ->
                stays != null && stays.plugin() == plugin ? stays : new PluginStays(plugin));
    }
}
