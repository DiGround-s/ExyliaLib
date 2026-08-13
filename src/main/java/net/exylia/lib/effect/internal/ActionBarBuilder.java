package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Timer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Builds an action bar.
 *
 * <pre>{@code
 * Effects.actionBar("{warning}Leaving in {highlight}%time%s")
 *         .countdown(5)
 *         .show(player);
 * }</pre>
 *
 * <p>An action bar shown without a timer or a duration stays until it is
 * stopped: the module keeps re-sending it, because the client fades one out
 * after about three seconds on its own.
 *
 * @since 1.4.0
 */
public final class ActionBarBuilder {

    /**
     * How often a static action bar is re-sent.
     *
     * <p>The client fades one after roughly sixty ticks, so this has to be
     * comfortably below that. Forty is frequent enough that no gap appears and
     * infrequent enough that it is not a per-tick packet.
     */
    private static final long KEEPALIVE_TICKS = 40;

    private final String text;
    private String timeStyle = "";
    private Timer timer;
    private long period = KEEPALIVE_TICKS;
    private Runnable onEnd;

    /** The owning plugin's name, stamped by {@code Effects.of(plugin)}. */
    private String owner;

    public ActionBarBuilder(String text) {
        this.text = text;
    }

    /**
     * Counts down, replacing {@code %time%} as it goes.
     *
     * @param seconds how long to count, decimals allowed
     * @return this builder
     */
    public @NotNull ActionBarBuilder countdown(double seconds) {
        this.timer = Timer.countdown(seconds);
        // A decimal countdown needs a redraw every tick to move smoothly.
        this.period = 1;
        return this;
    }

    /**
     * Counts upwards, replacing {@code %time%} as it goes.
     *
     * @return this builder
     */
    public @NotNull ActionBarBuilder countUp() {
        this.timer = Timer.countUp();
        this.period = 1;
        return this;
    }

    /**
     * Shows for a fixed time without counting anything.
     *
     * @param seconds how long it stays, decimals allowed
     * @return this builder
     */
    public @NotNull ActionBarBuilder duration(double seconds) {
        this.timer = Timer.countdown(seconds);
        return this;
    }

    /**
     * Keeps the action bar up until it is stopped.
     *
     * @return this builder
     */
    public @NotNull ActionBarBuilder permanent() {
        this.timer = null;
        this.period = KEEPALIVE_TICKS;
        return this;
    }

    /**
     * Sets how often the bar is redrawn.
     *
     * @param ticks ticks between redraws
     * @return this builder
     */
    public @NotNull ActionBarBuilder every(long ticks) {
        this.period = Math.max(1, ticks);
        return this;
    }

    /**
     * Chooses how {@code %time%} is written.
     *
     * @param style one of {@code auto}, {@code seconds}, {@code tenths},
     *              {@code hundredths}, {@code clock} or {@code full}
     * @return this builder
     */
    public @NotNull ActionBarBuilder timeStyle(@NotNull String style) {
        this.timeStyle = style;
        return this;
    }

    /**
     * Runs something when it ends.
     *
     * @param action what to run
     * @return this builder
     */
    public @NotNull ActionBarBuilder onEnd(@NotNull Runnable action) {
        this.onEnd = action;
        return this;
    }

    /**
     * Shows the action bar to one player.
     *
     * @param viewer who sees it
     * @return the display
     */
    /**
     * Stamps the owning plugin, so the display ticks on its scheduler and
     * stops when it disables. Called by {@code Effects.of(plugin)}; direct use
     * is fine too.
     *
     * @param pluginName the owning plugin's name
     * @return this builder
     */
    public @NotNull ActionBarBuilder ownedBy(@NotNull String pluginName) {
        this.owner = pluginName;
        return this;
    }

    public @NotNull Display show(@NotNull Player viewer) {
        Display display = new Displays.ActionBarDisplay(viewer,
                new Rendered(text, timeStyle), timer, period, owner).start();
        if (onEnd != null) {
            display.onEnd(onEnd);
        }
        return display;
    }

    /** Shows the action bar to everybody online. */
    public void showAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            show(player);
        }
    }
}
