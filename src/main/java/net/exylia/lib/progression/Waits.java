package net.exylia.lib.progression;

import net.exylia.lib.util.TimeFormats;
import net.exylia.lib.util.upgrade.Curve;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Prices read as time at the player's own income: "Ready in 12s" over a pad, the three goals on a scoreboard, and
 * rewards sized in minutes of income so they stay worth having at every stage. Pure numbers, any thread.
 */
public final class Waits {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    /** Waits longer than this mean nothing to a player, so they read as unknown. */
    private static final double TEN_YEARS = 10 * 365 * 86400.0;

    private Waits() {
        throw new AssertionError("No instances.");
    }

    /** Seconds until {@code coins} reach {@code price} at {@code perSecond}: 0 when they already do, -1 with no income. */
    public static double seconds(BigDecimal coins, BigDecimal price, BigDecimal perSecond) {
        BigDecimal missing = price.subtract(coins);
        if (missing.signum() <= 0) return 0;
        if (perSecond.signum() <= 0) return -1;
        return missing.divide(perSecond, Curve.MC).doubleValue();
    }

    /** {@link #seconds} with the income given per minute. */
    public static double secondsPerMinute(BigDecimal coins, BigDecimal price, BigDecimal perMinute) {
        return seconds(coins, price, perMinute.divide(SIXTY, Curve.MC));
    }

    /** A wait from {@link #seconds} as goals and prices read it ("2h 30m"), or null when it is none or too far. */
    public static @Nullable String text(double seconds) {
        if (seconds <= 0 || seconds > TEN_YEARS) return null;
        return TimeFormats.render(Math.ceil(seconds), TimeFormats.Style.COMPACT);
    }

    /** How far {@code coins} go towards {@code price}, 0 to 1. */
    public static double progress(BigDecimal coins, BigDecimal price) {
        if (price.signum() <= 0) return 1;
        return Math.max(0, Math.min(1, coins.divide(price, Curve.MC).doubleValue()));
    }

    /** {@code minutes} minutes of income, the per-minute income never below {@code floor}, rounded down to whole coins. */
    public static BigDecimal minutes(BigDecimal perMinute, BigDecimal floor, double minutes) {
        return perMinute.max(floor).multiply(BigDecimal.valueOf(Math.max(0, minutes)), Curve.MC)
                .setScale(0, RoundingMode.DOWN);
    }
}
