package net.exylia.lib.util.preview;

import net.exylia.lib.util.preview.internal.PreviewRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Showing one player an effect, against nothing.
 *
 * <pre>{@code
 * PluginPreviews previews = Previews.of(this);
 *
 * previews.show(player, effect.sequence(), () -> menu.reopen(player));
 * }</pre>
 *
 * <h2>What it does</h2>
 * Lifts the player to an empty patch of sky, holds them there, plays the effect
 * in front of them where only they can see it, and puts them back exactly where
 * they were.
 *
 * <h2>Why the sky rather than an emptied room</h2>
 * ExyliaCommons cleared the chunks around the player by sending them a whole
 * chunk through NMS. Doing that without NMS means sending every block as air,
 * and a four-chunk radius is about a million of them &mdash; megabytes to the
 * client, twice, for a three-second effect.
 *
 * <p>Somewhere with no blocks needs no packets at all. The background is
 * genuinely empty rather than pretending to be, and coming back is one
 * teleport.
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li><b>Only they see it.</b> The effect is sent to that player alone, and
 *       for its duration they are hidden from everyone and everyone is hidden
 *       from them.</li>
 *   <li><b>They come back.</b> Quitting, being kicked, dying, changing world,
 *       being teleported by another plugin, the plugin being disabled, the
 *       server stopping &mdash; each ends the preview the way that suits it, and
 *       a timer ends it if none of them do.</li>
 *   <li><b>They do not fall.</b> Held by flight rather than by resetting
 *       velocity every tick, which the client fights.</li>
 *   <li><b>Two at once do not meet.</b> Each preview claims its own patch of
 *       sky, across every plugin.</li>
 * </ul>
 *
 * @since 1.31.0
 */
public final class Previews {

    private static final Map<String, PluginPreviews> BY_PLUGIN = new ConcurrentHashMap<>();

    private Previews() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginPreviews of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginPreviews(plugin));
    }

    /**
     * Whether this player is being shown a preview right now.
     *
     * <p>Worth asking before anything that would fight it: a combat check, a
     * teleport, a scoreboard that follows the player's position.
     *
     * @param player the player
     * @return whether they are previewing
     */
    public static boolean isPreviewing(@NotNull Player player) {
        return PreviewRuntime.isPreviewing(player.getUniqueId());
    }

    /** How many previews are running across every plugin. */
    public static int active() {
        return PreviewRuntime.active();
    }

    /**
     * Ends one plugin's previews and forgets it.
     *
     * <p>Called by the library when the plugin is disabled, before its
     * scheduler goes away: putting a player back needs it.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        PreviewRuntime.endAllOf(pluginName);
        BY_PLUGIN.remove(pluginName);
    }

    /** Ends every preview on the server, on shutdown. */
    public static void releaseAll() {
        PreviewRuntime.endEverything();
        BY_PLUGIN.clear();
    }
}
