package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.effect.Timer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Builds a title.
 *
 * <pre>{@code
 * Effects.title("{primary}&lVICTORY")
 *         .subtitle("{letters}Well played")
 *         .times(0.5, 3, 0.5)
 *         .show(player);
 * }</pre>
 *
 * @since 1.4.0
 */
public final class TitleBuilder {

    private final String title;
    private String subtitle = "";
    private String timeStyle = "";

    private int fadeIn = 10;
    private int stay = 70;
    private int fadeOut = 20;

    private Timer timer;
    private long period = 1;
    private boolean permanent;
    private Runnable onEnd;

    public TitleBuilder(String title) {
        this.title = title;
    }

    /**
     * Sets the smaller line below the title.
     *
     * @param text the subtitle
     * @return this builder
     */
    public @NotNull TitleBuilder subtitle(@NotNull String text) {
        this.subtitle = text;
        return this;
    }

    /**
     * Sets how long the title fades in, stays and fades out.
     *
     * @param fadeIn  seconds to fade in, decimals allowed
     * @param stay    seconds fully visible
     * @param fadeOut seconds to fade out
     * @return this builder
     */
    public @NotNull TitleBuilder times(double fadeIn, double stay, double fadeOut) {
        this.fadeIn = (int) Ticks.fromSeconds(fadeIn);
        this.stay = (int) Ticks.fromSeconds(stay);
        this.fadeOut = (int) Ticks.fromSeconds(fadeOut);
        return this;
    }

    /**
     * Counts down, replacing {@code %time%} in the text as it goes.
     *
     * @param seconds how long to count, decimals allowed
     * @return this builder
     */
    public @NotNull TitleBuilder countdown(double seconds) {
        this.timer = Timer.countdown(seconds);
        // The title has to outlast the countdown, or it would fade while still
        // counting.
        this.stay = (int) Ticks.fromSeconds(seconds) + 20;
        this.fadeOut = 0;
        return this;
    }

    /**
     * Counts upwards, replacing {@code %time%} as it goes.
     *
     * @return this builder
     */
    public @NotNull TitleBuilder countUp() {
        this.timer = Timer.countUp();
        this.permanent = true;
        return this;
    }

    /**
     * Keeps the title on screen until it is stopped.
     *
     * @return this builder
     */
    public @NotNull TitleBuilder permanent() {
        this.permanent = true;
        return this;
    }

    /**
     * Sets how often the title is redrawn.
     *
     * <p>The default of one tick is what makes a decimal countdown move
     * smoothly. Raise it for text that changes slowly.
     *
     * @param ticks ticks between redraws
     * @return this builder
     */
    public @NotNull TitleBuilder every(long ticks) {
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
    public @NotNull TitleBuilder timeStyle(@NotNull String style) {
        this.timeStyle = style;
        return this;
    }

    /**
     * Runs something when the title ends.
     *
     * @param action what to run
     * @return this builder
     */
    public @NotNull TitleBuilder onEnd(@NotNull Runnable action) {
        this.onEnd = action;
        return this;
    }

    /**
     * Shows the title to one player.
     *
     * @param viewer who sees it
     * @return the display
     */
    public @NotNull Display show(@NotNull Player viewer) {
        if (permanent) {
            // A title with no expiry has to be re-sent, so it is given a stay of
            // zero and repeats.
            stay = 0;
            fadeOut = 0;
        }
        Display display = new Displays.TitleDisplay(viewer,
                new Rendered(title, timeStyle),
                new Rendered(subtitle, timeStyle),
                timer, period, fadeIn, stay, fadeOut).start();
        if (onEnd != null) {
            display.onEnd(onEnd);
        }
        return display;
    }

    /**
     * Shows the title to everybody online.
     *
     * <p>Each player gets their own display, because the text may contain
     * placeholders that differ per player.
     */
    public void showAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            show(player);
        }
    }
}
