package net.exylia.lib.util.snapshot;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's state, kept for later.
 *
 * <pre>{@code
 * private PluginSnapshots snapshots;
 *
 * @Override
 * public void onEnable() {
 *     snapshots = Snapshots.of(this);
 * }
 *
 * // Held in memory, for as long as a menu is open:
 * Snapshot before = snapshots.capture(player);
 * before.restoreTo(player);
 *
 * // Stored, for as long as it takes — a disconnect, a restart, a crash:
 * snapshots.saveAndClear(player, "ffa");
 * snapshots.restore(player, "ffa", lobby -> teleport(player, lobby));
 * }</pre>
 *
 * <h2>What it is for</h2>
 * Anything that takes a player's things away and promises to give them back: an
 * arena that hands out a kit, an event with its own inventory, a sandbox world,
 * a kit editor. The promise is the hard part, because the server can stop
 * between the taking and the giving, and a promise kept only when nothing goes
 * wrong is not one.
 *
 * <h2>One type, two lifetimes</h2>
 * A {@link Snapshot} is the same value whether it lives in a field or in a
 * table. Which it is depends on the method called, not on the type used, and
 * there is nothing to initialise: ExyliaCommons had a static
 * {@code SnapshotManager.initialize(plugin)}, which in a shared library means
 * the first plugin to call it owns the module and its {@code shutdown} takes it
 * away from every other plugin on the server.
 *
 * <h2>Context, and the bug it fixes</h2>
 * A stored snapshot is identified by the player <em>and</em> the reason it was
 * taken. ExyliaCommons stored one row per player, so a player who was in an FFA
 * arena and then joined an event had the arena snapshot overwritten by the event
 * one &mdash; and when the event gave them "their" inventory back, it gave them
 * the arena kit and destroyed what they owned.
 *
 * <h2>Migration</h2>
 * The first store opened by a plugin copies whatever ExyliaCommons left in
 * {@code snapshot_player_states} into the table this module owns, once, in the
 * background. It copies and never deletes, so a server can go back.
 *
 * <h2>Reload</h2>
 * Nothing here is derived from the palette, so there is no {@code invalidateAll}
 * and the palette reload does not reach this module. A snapshot holds items and
 * numbers; nothing in it is a component.
 *
 * @since 1.34.0
 */
public final class Snapshots {

    private static final Map<String, PluginSnapshots> BY_PLUGIN = new ConcurrentHashMap<>();

    private Snapshots() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginSnapshots of(@NotNull Plugin plugin) {
        // Reloaded in place: a store owned by a different Plugin object belongs
        // to the previous load, whose cleanup runs a tick after it was disabled
        // and has not had a tick yet.
        BY_PLUGIN.computeIfPresent(plugin.getName(), (ignored, snapshots) -> {
            if (snapshots.ownedBy(plugin)) {
                return snapshots;
            }
            snapshots.release();
            return null;
        });
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginSnapshots(plugin));
    }

    /**
     * Forgets one plugin's store.
     *
     * <p>Called by the library when the plugin is disabled. Nothing is written
     * on the way out and nothing needs to be: every stored snapshot was durable
     * the moment it was taken, which is the whole reason it is a row rather
     * than a field.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        PluginSnapshots snapshots = BY_PLUGIN.remove(pluginName);
        if (snapshots != null) {
            snapshots.release();
        }
    }

    /**
     * Forgets one load of a plugin's store, leaving a newer load's alone.
     *
     * <p>A plugin reloaded in place has two loads alive at once, because the
     * tool that reloaded it disabled and enabled within a single tick.
     *
     * @param plugin the load being let go
     * @since 1.64.0
     */
    public static void release(@NotNull Plugin plugin) {
        BY_PLUGIN.computeIfPresent(plugin.getName(), (ignored, snapshots) -> {
            if (!snapshots.ownedBy(plugin)) {
                return snapshots;
            }
            snapshots.release();
            return null;
        });
    }

    /** Forgets every plugin's store, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.values().forEach(PluginSnapshots::release);
        BY_PLUGIN.clear();
    }

    /** How many plugins hold a store, for diagnostics. */
    public static int active() {
        return BY_PLUGIN.size();
    }
}
