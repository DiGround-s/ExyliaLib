package net.exylia.lib.effect;

import org.jetbrains.annotations.NotNull;

/**
 * The clock behind a timed effect.
 *
 * <p>A timer is a value, not a task: it knows how long it has run and how long
 * it has left, and something else decides how often to look. That separation is
 * what lets the same timer drive a boss bar, an action bar and a title at once
 * without three of them counting independently and drifting apart.
 *
 * <h2>Direction</h2>
 * A {@link #countdown} runs from a duration to zero, which is what a countdown
 * boss bar wants. A {@link #countUp} runs from zero upwards and never finishes
 * on its own, which is what an elapsed-time display wants.
 *
 * <h2>Decimals</h2>
 * Time is kept in ticks, the only unit the server counts exactly, and read back
 * as a decimal number of seconds. That is what makes {@code 3.3s} display
 * correctly rather than jumping between whole seconds.
 *
 * @since 1.4.0
 */
public interface Timer {

    /**
     * Creates a timer that counts down to zero.
     *
     * @param seconds how long it runs, decimals allowed
     * @return the timer
     */
    static @NotNull Timer countdown(double seconds) {
        return new net.exylia.lib.effect.internal.SimpleTimer(Ticks.fromSeconds(seconds), true);
    }

    /**
     * Creates a timer that counts down, in ticks.
     *
     * @param ticks how long it runs
     * @return the timer
     */
    static @NotNull Timer countdownTicks(long ticks) {
        return new net.exylia.lib.effect.internal.SimpleTimer(Math.max(0, ticks), true);
    }

    /**
     * Creates a timer that counts upwards and never finishes.
     *
     * <p>For an elapsed-time display, such as how long a match has been
     * running.
     *
     * @return the timer
     */
    static @NotNull Timer countUp() {
        return new net.exylia.lib.effect.internal.SimpleTimer(0, false);
    }

    /**
     * Creates a timer that counts upwards towards a known total.
     *
     * <p>Progress is measured against the total, so a boss bar fills rather
     * than empties.
     *
     * @param seconds the total the timer counts towards, decimals allowed
     * @return the timer
     */
    static @NotNull Timer countUp(double seconds) {
        return new net.exylia.lib.effect.internal.SimpleTimer(Ticks.fromSeconds(seconds), false);
    }

    /**
     * Creates a timer that reads a running cooldown.
     *
     * <p>For showing a player a cooldown they already have, rather than
     * counting the same thing twice:
     *
     * <pre>{@code
     * Cooldowns.start(player, "pearl", Duration.ofSeconds(16));
     *
     * Effects.bossBar("<red>Pearl: %time%s")
     *        .timer(Timer.ofCooldown(player, "pearl"))
     *        .show(player);
     * }</pre>
     *
     * <p>The cooldown remains the truth: it is what other plugins see, what
     * survives a restart, and what decides when the bar is finished. This only
     * looks at it, so {@link #advance} and {@link #extend} do nothing — to give
     * the player more time, start the cooldown again.
     *
     * <p>The total is whatever is left when this is created, so make it while
     * the cooldown is fresh if the bar should start full.
     *
     * @param player the player whose cooldown to show
     * @param key    the cooldown's key
     * @return the timer
     * @since 1.12.0
     */
    static @NotNull Timer ofCooldown(@NotNull org.bukkit.entity.Player player,
                                     @NotNull String key) {
        return new net.exylia.lib.effect.internal.CooldownTimer(
                net.exylia.lib.util.CooldownScope.player(player.getUniqueId()), key);
    }

    /**
     * Creates a timer that reads a running cooldown, measured against a total
     * you name.
     *
     * <p>For a bar created after the cooldown started, which would otherwise
     * measure itself against whatever happened to be left.
     *
     * @param player       the player whose cooldown to show
     * @param key          the cooldown's key
     * @param totalSeconds what a full bar means
     * @return the timer
     * @since 1.12.0
     */
    static @NotNull Timer ofCooldown(@NotNull org.bukkit.entity.Player player,
                                     @NotNull String key, double totalSeconds) {
        return new net.exylia.lib.effect.internal.CooldownTimer(
                net.exylia.lib.util.CooldownScope.player(player.getUniqueId()),
                key, totalSeconds);
    }

    /**
     * Creates a timer that reads a cooldown belonging to any owner.
     *
     * <p>For a boss bar counting down something the whole server shares:
     *
     * <pre>{@code
     * Timer.ofCooldown(CooldownScope.GLOBAL, "world-boss");
     * }</pre>
     *
     * @param scope whose cooldown to show
     * @param key   the cooldown's key
     * @return the timer
     * @since 1.12.0
     */
    static @NotNull Timer ofCooldown(@NotNull net.exylia.lib.util.CooldownScope scope,
                                     @NotNull String key) {
        return new net.exylia.lib.effect.internal.CooldownTimer(scope, key);
    }

    /**
     * Advances the timer.
     *
     * <p>Called by whatever drives the effect. Advancing past the end clamps
     * rather than going negative.
     *
     * @param ticks how many ticks passed
     */
    void advance(long ticks);

    /**
     * Gives the timer more time to run.
     *
     * <p>A negative value takes time away, and can end the timer immediately.
     * This is not the same as {@link #advance}: advancing moves the clock
     * forward, while this moves the finish line.
     *
     * @param ticks how many ticks to add
     */
    void extend(long ticks);

    /**
     * Returns how much time is left, in seconds with decimals.
     *
     * <p>For a count-up timer with no total, this is zero.
     *
     * @return the seconds remaining
     */
    double remaining();

    /**
     * Returns how long the timer has run, in seconds with decimals.
     *
     * @return the seconds elapsed
     */
    double elapsed();

    /**
     * Returns how much time is left, in ticks.
     *
     * @return the ticks remaining
     */
    long remainingTicks();

    /**
     * Returns how long the timer has run, in ticks.
     *
     * @return the ticks elapsed
     */
    long elapsedTicks();

    /**
     * Returns the total this timer was created with, in seconds.
     *
     * @return the total, or zero for an open-ended count-up
     */
    double total();

    /**
     * Returns how far along the timer is, from 0 to 1.
     *
     * <p>A countdown returns 1 at the start and 0 at the end, so a boss bar
     * empties. A count-up towards a total returns the reverse, so it fills. An
     * open-ended count-up always returns 1, since there is nothing to measure
     * against.
     *
     * @return the progress, always between 0 and 1
     */
    float progress();

    /**
     * Returns whether the timer has reached its end.
     *
     * <p>An open-ended count-up is never finished.
     *
     * @return {@code true} when there is no time left
     */
    boolean finished();

    /**
     * Returns whether this timer runs towards zero.
     *
     * @return {@code true} for a countdown
     */
    boolean isCountdown();

    /**
     * Returns the time this timer should display, in seconds with decimals.
     *
     * <p>A countdown displays what is left; a count-up displays what has
     * passed. This is what {@code %time%} reads, so a template does not have to
     * know which direction the timer runs.
     *
     * @return the seconds to display
     */
    double displayed();
}
