package net.exylia.lib.respawn;

import net.exylia.lib.respawn.internal.RespawnRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where players respawn, and code that runs once they stand again, on Paper
 * and on Folia alike.
 *
 * <p>Folia fires neither {@code PlayerRespawnEvent} nor
 * {@code PlayerPostRespawnEvent} when a player respawns after dying, so a plain
 * listener is dead code there. This module detects the respawn itself on Folia
 * and drives the same rules from the events on Paper.
 *
 * <pre>{@code
 * Respawns.of(this).location(EventPriority.HIGH, ctx -> ctx.bedSpawn() ? null : spawn);
 * Respawns.of(this).after(player -> giveKit(player));
 * }</pre>
 *
 * @since 1.440.0
 */
public final class Respawns {

    private static final Map<String, PluginRespawns> BY_PLUGIN = new ConcurrentHashMap<>();

    private Respawns() {
    }

    /**
     * The respawn hooks of one plugin; the same instance every time.
     *
     * @param plugin the plugin registering rules and callbacks
     * @return its respawn hooks
     */
    public static @NotNull PluginRespawns of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginRespawns(plugin));
    }

    /**
     * Drops every rule and callback of a disabled plugin. Called by the library.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
        RespawnRuntime.release(pluginName);
    }
}
