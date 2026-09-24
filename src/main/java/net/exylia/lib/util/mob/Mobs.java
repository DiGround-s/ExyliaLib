package net.exylia.lib.util.mob;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom mobs: a template describes one, and it can be spawned anywhere.
 *
 * <pre>{@code
 * PluginMobs mobs = Mobs.of(this);
 * mobs.register(knight);                                   // from the plugin's own storage
 * mobs.spawn(knight, location).thenAccept(entity -> ...);  // anywhere, any thread
 * mobs.onDeath(death -> payOut(death));                    // the plugin pays, not the library
 * }</pre>
 *
 * <h2>What it does</h2>
 * A template is a living entity type with a name, equipment, attributes,
 * switches, potion effects and skills. This module applies all of it on spawn,
 * runs the skills, keeps the health in the name current, tracks who hurt each
 * mob, and tells the plugin when one dies.
 *
 * <h2>What it does not do</h2>
 * <ul>
 *   <li><b>It stores nothing.</b> The plugin keeps its templates wherever it
 *       keeps things and hands them in with {@link PluginMobs#register};
 *       {@link MobCodec} writes the parts that go in columns.</li>
 *   <li><b>It pays nothing.</b> The rewards and money on a template are the
 *       plugin's to give, through {@code Rewards} and its economy, from
 *       {@link PluginMobs#onDeath}. The library is not a feature plugin.</li>
 *   <li><b>It keeps nothing across a restart.</b> Mobs are not saved with their
 *       chunk; a spawner, an event or a boss fight spawns them again.</li>
 *   <li><b>It decides nothing about where mobs appear.</b> Spawners, caps and
 *       regions are the plugin's; {@link PluginMobs#count} is what they check.</li>
 * </ul>
 *
 * @since 1.192.0
 */
public final class Mobs {

    private static final Map<String, PluginMobs> BY_PLUGIN = new ConcurrentHashMap<>();

    private Mobs() {
        throw new AssertionError("No instances.");
    }

    /**
     * This plugin's mobs, started on first use.
     *
     * @param plugin the plugin
     * @return its mobs, the same instance every time
     */
    public static @NotNull PluginMobs of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginMobs(plugin));
    }

    /**
     * Takes one plugin's mobs out of the world and forgets its templates.
     *
     * <p>Called by the library when the plugin is disabled.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        PluginMobs mobs = BY_PLUGIN.remove(pluginName);
        if (mobs != null) mobs.stop();
    }

    /** Releases every plugin's mobs, on shutdown. */
    public static void releaseAll() {
        for (String pluginName : Map.copyOf(BY_PLUGIN).keySet()) {
            release(pluginName);
        }
    }
}
