package net.exylia.lib.trade;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * How a trade window looks and behaves. Immutable: each method answers a copy.
 *
 * <pre>{@code
 * TradeOptions options = TradeOptions.defaults()
 *         .title(messages.get("bet.title"))                       // %player% is the other player
 *         .info(() -> messages.get("bet.money").replace("%amount%", money))
 *         .allowEmpty(false)
 *         .timeout(Duration.ofMinutes(2));
 * }</pre>
 *
 * @param title      the window title, in Exylia text notation; {@code %player%}
 *                   is the other player's name. {@code null} for the library's
 *                   translated default
 * @param info       one extra line on the centre item, read on every redraw
 *                   (about once a second); {@code null} for none. It runs on
 *                   the viewer's thread: read memory, never a database
 * @param allowEmpty whether a side may confirm while offering nothing
 * @param timeout    how long the trade may stay open; zero for no limit
 * @param countdown  how long both confirmations must stand before the trade
 *                   completes; any change in that time resets it
 * @since 1.349.0
 */
public record TradeOptions(@Nullable String title, @Nullable Supplier<String> info, boolean allowEmpty,
                           @NotNull Duration timeout, @NotNull Duration countdown) {

    private static final TradeOptions DEFAULTS = new TradeOptions(null, null, true,
            Duration.ofMinutes(3), Duration.ofSeconds(3));

    public TradeOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(countdown, "countdown");
        if (timeout.isNegative() || countdown.isNegative()) {
            throw new IllegalArgumentException("A trade's timeout and countdown cannot be negative.");
        }
    }

    /** No title or info of the caller's, empty sides allowed, a 3-minute timeout and a 3-second countdown. */
    public static @NotNull TradeOptions defaults() {
        return DEFAULTS;
    }

    public @NotNull TradeOptions title(@Nullable String title) {
        return new TradeOptions(title, info, allowEmpty, timeout, countdown);
    }

    public @NotNull TradeOptions info(@Nullable Supplier<String> info) {
        return new TradeOptions(title, info, allowEmpty, timeout, countdown);
    }

    public @NotNull TradeOptions allowEmpty(boolean allowEmpty) {
        return new TradeOptions(title, info, allowEmpty, timeout, countdown);
    }

    public @NotNull TradeOptions timeout(@NotNull Duration timeout) {
        return new TradeOptions(title, info, allowEmpty, timeout, countdown);
    }

    public @NotNull TradeOptions countdown(@NotNull Duration countdown) {
        return new TradeOptions(title, info, allowEmpty, timeout, countdown);
    }
}
