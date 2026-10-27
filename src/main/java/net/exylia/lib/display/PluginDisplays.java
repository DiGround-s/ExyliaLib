package net.exylia.lib.display;

import net.exylia.lib.display.internal.DisplayRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * One plugin's view of the display module.
 *
 * <pre>{@code
 * PluginDisplays displays = Displays.of(this);
 *
 * displays.show(blade, thrown, victim.getLocation(), nearbyPlayers);
 * }</pre>
 *
 * <p>Obtained from {@link Displays#of}, which hands back the same instance every
 * time, so it can be asked for wherever one is needed rather than passed around.
 *
 * @since 1.85.0
 */
public final class PluginDisplays {

    private final String pluginName;

    PluginDisplays(@NotNull String pluginName) {
        this.pluginName = pluginName;
    }

    /**
     * Shows one display to a list of players.
     *
     * <p>It removes itself when its motion ends, so the handle is only worth
     * keeping if it might need to go sooner.
     *
     * @param model   what it draws
     * @param motion  how it moves
     * @param at      where it stands, before the motion's offsets
     * @param viewers who sees it; the list is kept, so hand over a list nobody
     *                else is going to change
     * @return the handle, or {@code null} when nobody can see it or the server
     *         has no PacketEvents
     */
    public @Nullable DisplayHandle show(@NotNull DisplayModel model, @NotNull DisplayMotion motion,
                                        @NotNull Location at, @NotNull List<Player> viewers) {
        return DisplayRuntime.show(pluginName, model, motion, at, viewers);
    }

    /**
     * As {@link #show(DisplayModel, DisplayMotion, Location, List)}, but drawn even while nobody is
     * listed: {@link DisplayHandle#viewers} lets players in later, in the pose it has reached by then.
     *
     * @since 1.370.0
     */
    public @Nullable DisplayHandle showKept(@NotNull DisplayModel model, @NotNull DisplayMotion motion,
                                            @NotNull Location at, @NotNull List<Player> viewers) {
        return DisplayRuntime.show(pluginName, model, motion, at, viewers, 0, true);
    }

    /**
     * Shows one display to whoever is within a radius of it, for as long as it
     * lasts.
     *
     * <pre>{@code
     * DisplayHandle wheel = displays.show(wheelModel, spinning, crate.above(2), 24);
     * }</pre>
     *
     * <p>Who is near is read again every half second: a player who walks up
     * mid-animation is shown it in the pose everyone else sees, and a player who
     * walks away is sent the destroy. Nobody being near when it starts is not a
     * reason to draw nothing, so the handle is only {@code null} when the
     * server cannot show displays or its budget is full.
     *
     * <p>For displays that stand somewhere. One seated on a player goes where
     * that player goes and keeps the viewers it was given; see {@link #rideOn}.
     *
     * @param model  what it draws
     * @param motion how it moves
     * @param at     where it stands, before the motion's offsets
     * @param radius how far away it can be seen, in blocks
     * @return the handle, or {@code null} when the server cannot show it
     * @since 1.272.0
     */
    public @Nullable DisplayHandle show(@NotNull DisplayModel model, @NotNull DisplayMotion motion,
                                        @NotNull Location at, double radius) {
        return show(model, motion, at, radius, null);
    }

    /**
     * The same, narrowed by a test on each viewer.
     *
     * <p>The test is where a "show effects: off" setting plugs in. It is asked
     * per nearby player on every re-read, on the thread that owns the place, so
     * it must be cheap and must read memory only.
     *
     * @param model  what it draws
     * @param motion how it moves
     * @param at     where it stands, before the motion's offsets
     * @param radius how far away it can be seen, in blocks
     * @param who    who may see it, or {@code null} for anybody in range
     * @return the handle, or {@code null} when the server cannot show it
     * @since 1.272.0
     */
    public @Nullable DisplayHandle show(@NotNull DisplayModel model, @NotNull DisplayMotion motion,
                                        @NotNull Location at, double radius,
                                        @Nullable Predicate<Player> who) {
        return DisplayRuntime.showTracked(pluginName, model, motion, at.clone(),
                new DisplayRuntime.Audience(Math.max(0.0, radius), who));
    }

    /**
     * Shows one display seated on something, so it goes where that goes.
     *
     * <p>For an effect that belongs to a player rather than to a place: a seal
     * under their feet, a shell around them, wings on their back. The client
     * carries it along with its mount, so nothing is sent while they move and
     * the effect never lags a tick behind them.
     *
     * <p>Its motion still applies, and applies from where the mount is: the
     * offsets in the motion are what put a ring's pieces around the player
     * rather than inside them, so a shape's own point has to be folded into the
     * motion rather than into {@code at}.
     *
     * @param model   what it draws
     * @param motion  how it moves, relative to the mount
     * @param ride    what it is seated on
     * @param viewers who sees it; the list is kept, so hand over a list nobody
     *                else is going to change
     * @return the handle, or {@code null} when nobody can see it or the server
     *         has no PacketEvents
     */
    public @Nullable DisplayHandle rideOn(@NotNull DisplayModel model, @NotNull DisplayMotion motion,
                                          @NotNull org.bukkit.entity.Entity ride,
                                          @NotNull List<Player> viewers) {
        return DisplayRuntime.show(pluginName, model, motion, ride.getLocation(), viewers,
                ride.getEntityId());
    }

    /**
     * Removes everything this plugin is showing.
     *
     * <p>Called for it when the plugin is disabled; worth calling by hand when
     * a whole feature is turned off at runtime.
     */
    public void removeAll() {
        DisplayRuntime.release(pluginName);
    }

    /** How many displays this plugin has on screen. */
    public int active() {
        return DisplayRuntime.active(pluginName);
    }

    /** Whether displays can be shown at all on this server. */
    public boolean isSupported() {
        return DisplayRuntime.isSupported();
    }
}
