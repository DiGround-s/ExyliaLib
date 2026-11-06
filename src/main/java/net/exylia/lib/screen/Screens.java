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
 * world, and Shift closes it. It also closes when they take damage, die, change world, quit, or when the
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

    /** How fast a player's cursor moves: 1 (the default) keeps it under the point their eyes turn to. */
    public static double sensitivity(@NotNull Player player) {
        return ScreenRuntime.sensitivity(player.getUniqueId());
    }

    /** Sets a player's cursor speed, from 0.2 to 3; it applies at once to an open screen. Kept until they quit. */
    public static void sensitivity(@NotNull Player player, double sensitivity) {
        ScreenRuntime.sensitivity(player.getUniqueId(), Math.max(0.2, Math.min(3.0, sensitivity)));
    }
}
