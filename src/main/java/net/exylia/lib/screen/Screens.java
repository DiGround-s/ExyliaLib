package net.exylia.lib.screen;

import net.exylia.lib.screen.internal.ScreenPreferences;
import net.exylia.lib.screen.internal.ScreenRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens {@link Screen}s for one plugin.
 *
 * <pre>{@code
 * Screens screens = Screens.of(this);
 * ScreenSession session = screens.open(player, hub);   // on the player's thread
 * screens.close(player);
 * }</pre>
 *
 * <p>While a screen is open the player stands still, their clicks and wheel go to the screen instead of the
 * world, and Shift closes it. It also closes when they take damage, die, change world, are teleported (since 1.485.1), a camera takes their view (since 1.518.4), quit, or when the
 * plugin that opened it is disabled. A player has one screen at a time: opening another replaces it.
 *
 * <p>Drawn with packets only, so it needs PacketEvents; without it {@link #open} returns {@code null} and logs
 * once.
 *
 * @since 1.483.0
 */
public final class Screens {

    private static final Map<String, Screens> BY_PLUGIN = new ConcurrentHashMap<>();

    private final Plugin plugin;

    private Screens(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The words a body's {@code wearing} takes for these items ({@link Node#wearing(String)}, {@code <img skin
     * wearing="...">}): armour finds its slot, leather keeps its colour and armour its trim, the first other item is
     * held. {@code null} and air are skipped. Give it to a screen as a row field or a value.
     *
     * <pre>{@code row.put("kit_wearing", Screens.outfit(helmet, chestplate, leggings, boots, sword));}</pre>
     *
     * @since 1.498.0
     */
    public static @NotNull String outfit(@Nullable org.bukkit.inventory.ItemStack... items) {
        return items == null ? "" : net.exylia.lib.screen.internal.Outfit.token(java.util.Arrays.asList(items));
    }

    /** The screens of {@code plugin}. */
    public static @NotNull Screens of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new Screens(plugin));
    }

    /**
     * Shows {@code screen} to {@code player}, closing whatever screen they had open. On the player's thread.
     *
     * <p>A camera holding the player (a cinematic, a replay, a spectator's target: anything that moved their view to
     * another entity) refuses the open, and a player gets a line saying why, since 1.518.4: a screen stands at their
     * eyes and could be neither seen nor clicked from there. A camera that starts closes an open screen.
     *
     * @return the open session, or {@code null} when PacketEvents is missing or a camera holds the player
     */
    public @Nullable ScreenSession open(@NotNull Player player, @NotNull Screen screen) {
        return ScreenRuntime.open(plugin, player, screen);
    }

    /** Closes the player's screen when this plugin opened it. */
    public void close(@NotNull Player player) {
        ScreenSession session = session(player);
        if (session != null) session.close();
    }

    /** The screen this plugin has open for the player, if any. */
    public @Nullable ScreenSession session(@NotNull Player player) {
        return ScreenRuntime.session(plugin, player);
    }

    /**
     * This plugin's screens kept as files in its data folder: {@code folder/<id>.html} for every language, words in
     * {@code lang/<code>/folder/<id>.yml}, installed from and updated with the jar's copies under the same path, and
     * read again when they change. Asking twice gives the same set.
     *
     * <pre>{@code ScreenMarkup hub = Screens.of(this).files(MyPlugin.class, "screens").markup("hub");}</pre>
     *
     * @param anchor a class packaged with the files
     * @param folder the folder inside the jar and the data folder, usually {@code screens}
     * @since 1.494.0
     */
    public @NotNull ScreenSet files(@NotNull Class<?> anchor, @NotNull String folder) {
        return ScreenSet.of(plugin, anchor, folder);
    }

    /**
     * Whether {@code target} is out of {@code viewer}'s sight only because the viewer's open screen hid them (a player
     * standing at the viewer's eyes would be drawn in front of it). {@link Player#canSee} says {@code false} for them
     * until the screen closes, so a list of players that follows {@code canSee} asks this too. On the viewer's thread.
     *
     * @since 1.518.5
     */
    public static boolean hides(@NotNull Player viewer, @NotNull Player target) {
        return ScreenRuntime.hides(viewer, target);
    }

    /** How fast a player's cursor moves: 1 (the default) keeps it under the point their eyes turn to. */
    public static double sensitivity(@NotNull Player player) {
        return ScreenPreferences.sensitivity(player.getUniqueId());
    }

    /**
     * Sets a player's cursor speed, from 0.1 to 10 (3 by default); it applies at once to an open screen. Kept in the
     * library's database, shared by every server on it since 1.556.0 (in the player's data on each server from 1.496.0),
     * and also set in {@code /settings} → Interface.
     */
    public static void sensitivity(@NotNull Player player, double sensitivity) {
        ScreenPreferences.sensitivity(player, sensitivity);
    }

    /**
     * Whether screens move as little as they can for this player: no arrivals, departures or animations, short
     * straight glides. Their own choice ({@code /settings} → Interface) or the server's ({@code screens.yml}).
     *
     * @since 1.496.0
     */
    public static boolean reducedMotion(@NotNull Player player) {
        return ScreenPreferences.reducedMotion(player.getUniqueId());
    }

    /** Sets a player's own reduced motion, kept like {@link #sensitivity(Player, double)}. @since 1.496.0 */
    public static void reducedMotion(@NotNull Player player, boolean reduced) {
        ScreenPreferences.reducedMotion(player, reduced);
    }

    /**
     * Redraws now every screen this plugin has open whose session passes {@code which}, rather than at its next
     * second: a queue that changed redraws every open hub at once.
     *
     * <pre>{@code Screens.of(plugin).redrawAll(session -> "play".equals(session.state("tab", "")));}</pre>
     *
     * @since 1.496.0
     */
    public void redrawAll(@NotNull java.util.function.Predicate<ScreenSession> which) {
        ScreenRuntime.redrawAll(plugin, which);
    }

    /** Redraws every screen this plugin has open. @since 1.496.0 */
    /**
     * The heaviest a screen of this plugin named {@code name} ({@link Screen#name}) has been for any viewer since the
     * server started, {@link ScreenCost#NONE} when it has not been drawn. For a plugin's check command, so an owner
     * sees a screen that is heavy for players' games before the players do.
     *
     * @since 1.565.0
     */
    public @NotNull ScreenCost cost(@NotNull String name) {
        return net.exylia.lib.screen.internal.ScreenCosts.of(plugin, name);
    }

    public void redrawAll() {
        redrawAll(session -> true);
    }

    /**
     * How large {@code player}'s screens are drawn, in percent: {@code screen-size} in {@code screens.yml} since 1.587.0,
     * when players stopped choosing their own (fitting the view to their FOV does it). @since 1.521.0
     */
    public static int size(@NotNull Player player) {
        return ScreenPreferences.size(player.getUniqueId());
    }

    /**
     * Did nothing since 1.587.0: screens fit each player's FOV and window instead of a size they choose.
     *
     * @deprecated players fit their view once ({@code /settings} → Interface, or a screen's settings); the size for
     *             everyone is {@code screen-size} in {@code screens.yml}
     * @since 1.521.0
     */
    @Deprecated(since = "1.587.0")
    public static void size(@NotNull Player player, @Nullable Integer percent) {
    }
}
