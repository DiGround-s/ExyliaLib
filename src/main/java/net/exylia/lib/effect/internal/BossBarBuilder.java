package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Timer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Builds a boss bar.
 *
 * <pre>{@code
 * Effects.bossBar("{primary}Starting in {highlight}%time%s")
 *         .countdown(10)
 *         .colour("PURPLE")
 *         .onEnd(this::startMatch)
 *         .show(player);
 * }</pre>
 *
 * <p>With a countdown the bar empties as time runs out. With
 * {@link #countUp(double)} it fills instead. With no timer at all it stays full
 * and on screen until stopped, which is what a status bar wants.
 *
 * @since 1.4.0
 */
public final class BossBarBuilder {

    private final String text;
    private String timeStyle = "";
    private String colour = "PURPLE";
    private String overlay = "PROGRESS";
    private Timer timer;
    private Float fixedProgress;
    private long period = 1;
    private Runnable onEnd;

    /** The owning plugin's name, stamped by {@code Effects.of(plugin)}. */
    private String owner;

    public BossBarBuilder(String text) {
        this.text = text;
    }

    /**
     * Counts down to zero, emptying the bar.
     *
     * @param seconds how long to count, decimals allowed
     * @return this builder
     */
    public @NotNull BossBarBuilder countdown(double seconds) {
        this.timer = Timer.countdown(seconds);
        return this;
    }

    /**
     * Counts upwards with no end, leaving the bar full.
     *
     * <p>For an elapsed-time display, such as how long a match has run.
     *
     * @return this builder
     */
    public @NotNull BossBarBuilder countUp() {
        this.timer = Timer.countUp();
        return this;
    }

    /**
     * Counts upwards towards a total, filling the bar.
     *
     * @param seconds the total to count towards, decimals allowed
     * @return this builder
     */
    public @NotNull BossBarBuilder countUp(double seconds) {
        this.timer = Timer.countUp(seconds);
        return this;
    }

    /**
     * Sets the fill directly, ignoring any timer.
     *
     * <p>For a bar that shows something other than time, such as remaining
     * health or a capture percentage.
     *
     * @param progress from 0 to 1
     * @return this builder
     */
    public @NotNull BossBarBuilder progress(float progress) {
        this.fixedProgress = Math.clamp(progress, 0f, 1f);
        return this;
    }

    /**
     * Sets the bar colour.
     *
     * @param name one of {@code PINK}, {@code BLUE}, {@code RED},
     *             {@code GREEN}, {@code YELLOW}, {@code PURPLE} or
     *             {@code WHITE}; an unknown name falls back to purple
     * @return this builder
     */
    public @NotNull BossBarBuilder colour(@NotNull String name) {
        this.colour = name;
        return this;
    }

    /**
     * Sets whether the bar is segmented.
     *
     * @param name one of {@code PROGRESS}, {@code NOTCHED_6},
     *             {@code NOTCHED_10}, {@code NOTCHED_12} or {@code NOTCHED_20}
     * @return this builder
     */
    public @NotNull BossBarBuilder overlay(@NotNull String name) {
        this.overlay = name;
        return this;
    }

    /**
     * Sets how often the bar is redrawn.
     *
     * @param ticks ticks between redraws
     * @return this builder
     */
    public @NotNull BossBarBuilder every(long ticks) {
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
    public @NotNull BossBarBuilder timeStyle(@NotNull String style) {
        this.timeStyle = style;
        return this;
    }

    /**
     * Runs something when the bar ends.
     *
     * @param action what to run
     * @return this builder
     */
    public @NotNull BossBarBuilder onEnd(@NotNull Runnable action) {
        this.onEnd = action;
        return this;
    }

    /**
     * Shows the bar to one player.
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
    public @NotNull BossBarBuilder ownedBy(@NotNull String pluginName) {
        this.owner = pluginName;
        return this;
    }

    public @NotNull Display show(@NotNull Player viewer) {
        Display display = new Displays.BossBarDisplay(viewer,
                new Rendered(text, timeStyle), timer, period,
                colour, overlay, fixedProgress, owner).start();
        if (onEnd != null) {
            display.onEnd(onEnd);
        }
        return display;
    }

    /** Shows the bar to everybody online. */
    public void showAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            show(player);
        }
    }
}
