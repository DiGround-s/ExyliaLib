package net.exylia.lib.util.showcase;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places that show a plugin's cosmetics off on their own, one after another,
 * for as long as somebody is there to watch.
 *
 * <pre>{@code
 * PluginShowcases showcases = Showcases.of(this)
 *         .visibleTo(player -> players.seesOthers(player))
 *         .start(() -> config.get().showcase(),
 *                 placed -> config.update(c -> c.withShowcase(c.showcase().withLocations(placed))),
 *                 stage -> playOne(stage));
 *
 * // An admin command
 * showcases.add(admin.getLocation());
 *
 * // On reload
 * showcases.rebuild();
 * }</pre>
 *
 * <h2>What is shared and what is not</h2>
 * A kill effect needs two bodies and a sequence, an armour trim one body turning
 * on the spot, an arrow trail a flight. What they share is everything around
 * that: a list of places in the plugin's configuration, admin commands that add
 * and remove them, a loop per place that waits for the last turn and its rest to
 * be over, starts nothing while nobody is near, never plays the same cosmetic
 * twice in a row and takes everything away on reload and disable. That is this
 * module; the turn itself is the plugin's {@link ShowcaseAct}.
 *
 * <h2>Cost</h2>
 * Every place ticks on its own region once a second. With nobody within its
 * radius it walks the world's player list and does nothing else.
 *
 * <h2>Nobody is acted on</h2>
 * A showcase is asked for by nobody. Whatever its act draws, it must not send a
 * title, run a command, give a potion or take a camera from somebody walking
 * past a lobby decoration: a plugin that plays configured sequences strips those
 * lines before it plays them.
 *
 * @since 1.188.0
 */
public final class Showcases {

    private static final Map<String, PluginShowcases> BY_PLUGIN = new ConcurrentHashMap<>();

    private Showcases() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginShowcases of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginShowcases(plugin));
    }

    /**
     * Stops one plugin's showcases and forgets it.
     *
     * <p>Called by the library when the plugin is disabled, while its scheduler
     * can still cancel what it started.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        PluginShowcases showcases = BY_PLUGIN.remove(pluginName);
        if (showcases != null) {
            showcases.stop();
        }
    }

    /** Stops every plugin's showcases, on shutdown. */
    public static void releaseAll() {
        for (String pluginName : Map.copyOf(BY_PLUGIN).keySet()) {
            release(pluginName);
        }
    }

    /** How many showcases are standing across every plugin, for diagnostics. */
    public static int active() {
        return BY_PLUGIN.values().stream().mapToInt(PluginShowcases::active).sum();
    }
}
