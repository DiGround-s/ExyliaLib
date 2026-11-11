package net.exylia.lib.hud;

import net.exylia.lib.guide.internal.GuideRuntime;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text on a player's screen that never takes their controls: widgets that stay (money, health, a quest tracker),
 * notices that pop in and out ("+120 coins", a kill), and big centre moments ("DOUBLE KILL").
 *
 * <pre>{@code
 * Huds huds = Huds.of(this);
 * HudHandle money = huds.show(player, HudWidget.of("money", HudAnchor.TOP_LEFT, p -> balanceLine(p)));
 * huds.notify(player, HudNotice.of(killLine).group("kill"));
 * huds.notify(player, HudNotice.moment(Text.of("{error}&lDOUBLE KILL").build()));
 * }</pre>
 *
 * <h2>How it stays put</h2>
 * Each element is a text display riding the player on their client alone, the way a guide's view text does
 * ({@code docs/guide.md}): the client carries it with the view every frame, so walking, jumping and turning cost no
 * packet and nothing trails. Only a changed text, a condition flipping, or a pitch change of a few degrees sends
 * anything. One display per element, nothing layered.
 *
 * <p>It steps aside while a screen is open, while a camera shot plays, while dead and in spectator mode, and comes back
 * after. Every method is safe from any thread; without PacketEvents nothing is drawn and the handles still work.
 *
 * @since 1.566.0
 */
public final class Huds {

    private static final Map<String, Huds> BY_PLUGIN = new ConcurrentHashMap<>();

    private final Plugin plugin;

    private Huds(Plugin plugin) {
        this.plugin = plugin;
    }

    public static @NotNull Huds of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new Huds(plugin));
    }

    /**
     * Puts a widget on the player's HUD, or replaces the one with the same id in place.
     *
     * @return its handle
     */
    public @NotNull HudHandle show(@NotNull Player player, @NotNull HudWidget widget) {
        return GuideRuntime.hud(plugin, player).show(widget);
    }

    /** Slides a notice onto the player's HUD. */
    public void notify(@NotNull Player player, @NotNull HudNotice notice) {
        GuideRuntime.hud(plugin, player).notify(notice);
    }

    /** {@link #notify(Player, HudNotice)} with a three-second notice at the top right. */
    public void notify(@NotNull Player player, @NotNull Component text) {
        notify(player, HudNotice.of(text));
    }

    /** Takes the widget with this id off the player's HUD; nothing when there is none. */
    public void remove(@NotNull Player player, @NotNull String id) {
        GuideRuntime.hud(plugin, player).remove(id);
    }

    /** Takes everything this plugin shows on the player's HUD off it. */
    public void clear(@NotNull Player player) {
        GuideRuntime.hud(plugin, player).clear();
    }

    /**
     * Hides every plugin's HUD for this player, or shows it again: a player's own choice. Kept on the player, so it
     * lasts across restarts. On the player's thread.
     */
    public static void hidden(@NotNull Player player, boolean hide) {
        GuideRuntime.hudHidden(player, hide);
    }

    /** Whether the player chose to hide the HUD. */
    public static boolean hidden(@NotNull Player player) {
        return GuideRuntime.hudHidden(player);
    }

    /**
     * Whether this player's client can draw a HUD at all: PacketEvents is here, the player is on Java (not Bedrock
     * through Geyser) and their client is 1.20.2 or newer, the floor screens share. An unknown client version counts as
     * the server's. A HUD shown to a player who fails this is never drawn.
     *
     * @since 1.591.0
     */
    public static boolean supports(@NotNull Player player) {
        return net.exylia.lib.ui.MenuStyles.canShowScreens(player);
    }

    /**
     * Whether a HUD shown to this player is drawn: {@link #supports(Player) supported} and not {@link #hidden(Player)
     * hidden} by their own choice. The test for showing something else instead (a sidebar, a boss bar); also
     * {@code %player_hud%}.
     *
     * @since 1.591.0
     */
    public static boolean visible(@NotNull Player player) {
        return supports(player) && !hidden(player);
    }
}
