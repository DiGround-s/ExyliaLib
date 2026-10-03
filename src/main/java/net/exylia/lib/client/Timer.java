package net.exylia.lib.client;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * A timer drawn by a modified client on its HUD.
 *
 * <p>The client counts on its own, so the server sends a timer once and again
 * only when it changes — paused, resumed, or set to a new value:
 *
 * <pre>{@code
 * clients.timers().show(players, Timer.countdown("koth", Component.text("Castle"), remaining));
 * // the event is paused
 * clients.timers().show(players, Timer.countdown("koth", Component.text("Castle"), remaining).paused(true));
 * }</pre>
 *
 * <p>Showing a timer with a name already on screen replaces it.
 *
 * @param name      the handle it is removed by, and what the client keys it on
 * @param label     what the client writes next to it
 * @param countdown {@code true} to count down to zero, {@code false} to count up
 * @param value     where it starts: the time left, or the time already elapsed
 * @param paused    whether it is frozen at {@code value}
 * @param colour    its colour, {@code null} for the client's default
 * @since 1.232.0
 */
public record Timer(@NotNull String name, @NotNull Component label, boolean countdown,
                    @NotNull Duration value, boolean paused, @Nullable TextColor colour) {

    public Timer {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a timer needs a name");
        }
        if (label == null) {
            label = Component.empty();
        }
        if (value == null || value.isNegative()) {
            value = Duration.ZERO;
        }
    }

    /**
     * A timer counting down to zero.
     *
     * @param name  its handle
     * @param label what the client writes next to it
     * @param left  how long is left
     * @return the timer
     */
    public static @NotNull Timer countdown(@NotNull String name, @NotNull Component label, @NotNull Duration left) {
        return new Timer(name, label, true, left, false, null);
    }

    /**
     * A timer counting up.
     *
     * @param name    its handle
     * @param label   what the client writes next to it
     * @param elapsed how long has already passed
     * @return the timer
     */
    public static @NotNull Timer stopwatch(@NotNull String name, @NotNull Component label, @NotNull Duration elapsed) {
        return new Timer(name, label, false, elapsed, false, null);
    }

    /**
     * Returns this timer frozen or running.
     *
     * @param paused whether it is frozen
     * @return the timer
     */
    public @NotNull Timer paused(boolean paused) {
        return new Timer(name, label, countdown, value, paused, colour);
    }

    /**
     * Returns this timer in a colour.
     *
     * @param colour the colour
     * @return the timer
     */
    public @NotNull Timer colour(@Nullable TextColor colour) {
        return new Timer(name, label, countdown, value, paused, colour);
    }
}
