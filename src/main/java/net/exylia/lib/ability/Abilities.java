package net.exylia.lib.ability;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Triggered abilities: "when this happens to whoever holds it, if these hold,
 * roll, then do these".
 *
 * <pre>{@code
 * PluginAbilities abilities = Abilities.of(this);
 * Ability bleed = abilities.compile(section, problem -> getLogger().warning(problem));
 * abilities.holdings(player -> enchants.heldBy(player));
 * }</pre>
 *
 * <p>One {@link PluginAbilities} per plugin, created on first use and released
 * by ExyliaLib when the plugin disables. See {@code docs/ability.md}.
 *
 * @since 1.385.0
 */
public final class Abilities {

    private static final Map<String, PluginAbilities> BY_PLUGIN = new ConcurrentHashMap<>();

    private Abilities() {
        throw new AssertionError("No instances.");
    }

    /**
     * A plugin's abilities; the same object on every call.
     *
     * <p>The first call registers the plugin's listeners, so call it from
     * {@code onEnable} or later.
     *
     * @param plugin the owner
     * @return its abilities
     */
    public static @NotNull PluginAbilities of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> PluginAbilities.create(plugin));
    }

    /** Releases a plugin's abilities: caches, timers, passive effects, area breaks. ExyliaLib calls it on disable. */
    public static void release(@NotNull String pluginName) {
        PluginAbilities abilities = BY_PLUGIN.remove(pluginName);
        if (abilities != null) abilities.release();
        AreaBreaks.release(pluginName);
    }

    /** Releases every plugin's abilities. */
    public static void releaseAll() {
        BY_PLUGIN.keySet().forEach(Abilities::release);
        AreaBreaks.releaseAll();
    }
}
