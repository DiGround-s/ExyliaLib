package net.exylia.lib.guide;

import net.exylia.lib.client.Clients;
import net.exylia.lib.guide.internal.GuideRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Showing one player where to go and what to do, the way a game does it.
 *
 * <pre>{@code
 * PluginGuides guides = Guides.of(this);
 *
 * GuideText panel = guides.companion(player, Text.of("{primary}&lTALK TO THE SMITH").build());
 * GuideBeacon beacon = guides.beacon(player, smithy, BeaconStyle.of(glass, 0x8A51C4),
 *         metres -> Text.of("{letters}Smith {letters_black}» {info}" + Math.round(metres) + " m").build());
 * GuideTrail trail = guides.trail(player, world, route, TrailStyle.of(concrete, 0x8A51C4));
 * guides.glow(player, smithNpc, NamedTextColor.LIGHT_PURPLE);
 *
 * panel.text(Text.of("{primary}&lTALK TO THE SMITH\n{letters}Progress {info}1/2").build());
 * guides.clear(player);              // the step is done
 * }</pre>
 *
 * <h2>What there is</h2>
 * <ul>
 *   <li>{@linkplain PluginGuides#companion Companion text}: a panel floating
 *       ahead of the player's eyes that glides after their view and steps
 *       aside while they run, glide or fight.</li>
 *   <li>{@linkplain PluginGuides#anchored Anchored text}: a sign at a place,
 *       growing with distance so it stays readable.</li>
 *   <li>{@linkplain PluginGuides#beacon Beacons}: a tall beam of light over a
 *       target with a live distance, and a stand-in on the horizon when the
 *       target is too far for the client to draw.</li>
 *   <li>{@linkplain PluginGuides#trail Trails}: markers on the ground for the
 *       next stretch of a route, unrolling as the player walks it, with a way
 *       back when they wander off.</li>
 *   <li>{@linkplain PluginGuides#waypoint Waypoints}: the 1.21.6 locator bar,
 *       and Lunar's or Feather's minimap.</li>
 *   <li>{@linkplain PluginGuides#glow(Player, org.bukkit.entity.Entity,
 *       net.kyori.adventure.text.format.NamedTextColor) Glow}: an entity, a
 *       packet body or a block outlined for this player only.</li>
 * </ul>
 *
 * <h2>Nothing the server carries</h2>
 * Every piece is packets to one player: not an entity, not ticked, not saved,
 * not in any chunk, and invisible to everyone else standing there. What that
 * costs is that nothing else will take it away, so the module does: a piece
 * goes when it is removed, when its player leaves, when its plugin is
 * disabled, or when the server stops. A death or a change of world is not an
 * end; the client threw the entities away and they are drawn again on the
 * player's next tick.
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread. Each player's guidance is drawn by one
 * timer on that player, so on Folia it only ever reads the player from their
 * own region thread. Handle methods change what the next draw sends; they
 * never send from the caller's thread.
 *
 * <h2>Requirements</h2>
 * PacketEvents. Without it {@link #isSupported()} is {@code false}, every
 * method still returns a handle, and nothing is drawn (a waypoint still
 * reaches Lunar and Feather). A Bedrock player behind Geyser sees display
 * entities poorly or not at all; ask {@link #isBedrock(Player)} and fall back
 * to chat.
 *
 * @since 1.391.0
 */
public final class Guides {

    private static final Map<String, PluginGuides> BY_PLUGIN = new ConcurrentHashMap<>();

    private Guides() {
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginGuides of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new PluginGuides(plugin));
    }

    /**
     * Takes down everything one plugin shows and forgets it.
     *
     * <p>Called by the library when a plugin is disabled, while its players
     * are still here to be sent the destroy.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
        GuideRuntime.release(pluginName);
    }

    /** Takes down everything, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
        GuideRuntime.releaseAll();
    }

    /** Whether this server can draw guidance at all. */
    public static boolean isSupported() {
        return GuideRuntime.isSupported();
    }

    /**
     * Whether a player joined through Geyser or Floodgate.
     *
     * <p>Bedrock clients draw display entities poorly or not at all, and have
     * no locator bar to send a waypoint to. A plugin that guides somebody
     * should tell them in chat instead.
     *
     * @param player the player
     * @return {@code true} for a Bedrock player
     */
    public static boolean isBedrock(@NotNull Player player) {
        return Clients.isBedrock(player);
    }

    /** How many players have guidance from any plugin, for diagnostics. */
    public static int active() {
        return GuideRuntime.active();
    }
}
