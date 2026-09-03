package net.exylia.lib.effect;

import net.exylia.lib.text.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A showing effect that can be changed or stopped.
 *
 * <p>Anything that stays on screen returns one of these: a boss bar, a
 * persistent title, a repeating action bar. Anything that happens once, such as
 * a sound or a burst of particles, does not, because there would be nothing to
 * hold.
 *
 * <p>An effect that is never stopped is a leak the player can see, so a display
 * always ends one of three ways: its timer finishes, {@link #stop()} is called,
 * or the plugin that created it is disabled.
 *
 * <pre>{@code
 * Display bar = Effects.bossBar("{primary}Starting in {highlight}%time%s")
 *         .countdown(10)
 *         .show(player);
 *
 * bar.stop();   // or let the countdown end it
 * }</pre>
 *
 * <p>Every method is safe to call from any thread, and safe to call after the
 * effect has already ended.
 *
 * @since 1.4.0
 */
public interface Display {

    /**
     * Stops the effect and clears it from the screen.
     *
     * <p>Does nothing when already stopped, so a caller never has to check
     * first.
     */
    void stop();

    /**
     * Returns whether the effect is still showing.
     *
     * @return {@code true} until it is stopped or its timer finishes
     */
    boolean isShowing();

    /**
     * Returns the timer driving this effect.
     *
     * @return the timer, or {@code null} when the effect is not timed
     */
    @Nullable Timer timer();

    /**
     * Replaces the text of a showing effect.
     *
     * <p>The text goes through the same colour and placeholder handling as the
     * original, so {@code %time%} keeps working.
     *
     * @param text the new text
     * @return this display
     */
    @NotNull Display text(@NotNull String text);

    /**
     * Replaces the text of a showing effect with values already substituted.
     *
     * <p>The way to drive a bar whose numbers change every redraw. The values
     * are substituted on the parsed component rather than in the string, so
     * the template parses once however often the numbers change; a bar handed
     * {@code "Vida: 14.3"} as a string parses a new string every time.
     *
     * <pre>{@code
     * bar.text(Text.of(template).with("%hp%", health));
     * }</pre>
     *
     * @param text the new text with its values
     * @return this display
     */
    @NotNull Display text(@NotNull Text text);

    /**
     * Adds time to a running timer.
     *
     * <p>Negative values take time away. Does nothing when the effect is not
     * timed.
     *
     * @param seconds how many seconds to add, decimals allowed
     * @return this display
     */
    @NotNull Display addTime(double seconds);

    /**
     * Runs something when the effect ends.
     *
     * <p>Called exactly once, whether it ended by timer, by {@link #stop()} or
     * because the plugin was disabled. That makes it the right place to start
     * whatever the countdown was counting to.
     *
     * <p>Runs on the thread that owns the viewer, so it is safe to touch the
     * game from here.
     *
     * @param action what to run
     * @return this display
     */
    @NotNull Display onEnd(@NotNull Runnable action);

    /**
     * Sets the fill of a boss bar.
     *
     * <p>Only meaningful on boss bars; ignored by other effect types.
     *
     * @param progress from 0.0 (empty) to 1.0 (full)
     * @return this display
     */
    @NotNull Display progress(float progress);
}
