package net.exylia.lib.screen;

import net.exylia.lib.screen.internal.WorldBoard;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link Screen} placed in the world as a flat panel: a leaderboard on a wall, a menu by a portal. Every player
 * within range sees it with their own session (its own state, so their own page and tab), their own hover, and
 * clicks with the crosshair: a swing or a use on a button presses it, a click elsewhere goes to the world.
 * Nobody is frozen and the panel never turns to follow the view.
 *
 * <pre>{@code
 * WorldScreen board = WorldScreen.place(plugin, location, screen, 3.0);   // 3 blocks wide
 * ...
 * board.remove();                                                          // on disable
 * }</pre>
 *
 * <p>The location is the panel's middle and its yaw the way the panel faces (0 south, 90 west, 180 north,
 * -90 east). Its height follows the screen's {@linkplain Screen#size shape}. Boxes, outlines and text are drawn;
 * items, pictures and motion are not. A redraw each second picks up what placeholders show.
 *
 * @since 1.606.0
 */
public final class WorldScreen {

    private final WorldBoard board;

    private WorldScreen(WorldBoard board) {
        this.board = board;
    }

    /**
     * Puts {@code screen} in the world and starts drawing it to whoever comes near.
     *
     * @param width blocks across
     */
    public static @NotNull WorldScreen place(@NotNull Plugin plugin, @NotNull Location centre, @NotNull Screen screen,
                                             double width) {
        WorldBoard board = new WorldBoard(plugin, centre, screen, width);
        board.start();
        return new WorldScreen(board);
    }

    /** How near a player must be to see it, in blocks: 32 unless set. Keep it under the server's view distance. */
    public @NotNull WorldScreen range(double blocks) {
        board.range(blocks);
        return this;
    }

    /** How far the crosshair reaches its buttons, in blocks: 12 unless set. */
    public @NotNull WorldScreen reach(double blocks) {
        board.reach(blocks);
        return this;
    }

    /** Draws it again for every viewer now, not at the next second. */
    public void redraw() {
        board.redrawAll();
    }

    /** How many players see it. */
    public int viewers() {
        return board.viewers();
    }

    /** Takes it out of the world for everyone and stops it. */
    public void remove() {
        board.remove();
    }
}
