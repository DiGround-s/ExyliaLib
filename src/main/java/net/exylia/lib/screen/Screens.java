package net.exylia.lib.screen;

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
 * world, and Shift closes it. It also closes when they take damage, die, change world, are teleported (since 1.485.1), quit, or when the
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
     * @return the open session, or {@code null} when PacketEvents is missing
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

    /** How fast a player's cursor moves: 1 (the default) keeps it under the point their eyes turn to. */
    public static double sensitivity(@NotNull Player player) {
        return ScreenRuntime.sensitivity(player.getUniqueId());
    }

    /**
     * Sets a player's cursor speed, from 0.2 to 3; it applies at once to an open screen. Kept in the player's data
     * across restarts since 1.496.0 (until they quit before), and also set in {@code /settings} → Interface.
     */
    public static void sensitivity(@NotNull Player player, double sensitivity) {
        ScreenRuntime.sensitivity(player, sensitivity);
    }

    /**
     * Whether screens move as little as they can for this player: no arrivals, departures or animations, short
     * straight glides. Their own choice ({@code /settings} → Interface) or the server's ({@code screens.yml}).
     *
     * @since 1.496.0
     */
    public static boolean reducedMotion(@NotNull Player player) {
        return ScreenRuntime.reducedMotion(player.getUniqueId());
    }

    /** Sets a player's own reduced motion, kept in their data. @since 1.496.0 */
    public static void reducedMotion(@NotNull Player player, boolean reduced) {
        ScreenRuntime.reducedMotion(player, reduced);
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
    public void redrawAll() {
        redrawAll(session -> true);
    }
}
