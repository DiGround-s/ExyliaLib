package net.exylia.lib.effect.internal;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Renders a timer's seconds the way a player should read them.
 *
 * <p>Formatting lives here so a config can say how time looks without a plugin
 * formatting by hand, and so {@code %time%} means the same thing everywhere.
 *
 * <p>Symbols are fixed to a known locale: a server whose default locale uses a
 * comma for decimals would otherwise render {@code 3,3s} for some owners and
 * {@code 3.3s} for others from the same config.
 */
public final class TimeFormats {

    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);

    /**
     * One decimal, which is what a countdown showing {@code 3.3} needs.
     *
     * <p>{@link DecimalFormat} is not thread safe and effects render from
     * whichever thread owns the viewer, so each thread gets its own.
     */
    private static final ThreadLocal<DecimalFormat> ONE_DECIMAL =
            ThreadLocal.withInitial(() -> {
                DecimalFormat format = new DecimalFormat("0.0", SYMBOLS);
                // Half-even would render 0.25 as "0.2", which reads as a stall
                // when a countdown passes it.
                format.setRoundingMode(RoundingMode.HALF_UP);
                return format;
            });

    private static final ThreadLocal<DecimalFormat> TWO_DECIMALS =
            ThreadLocal.withInitial(() -> {
                DecimalFormat format = new DecimalFormat("0.00", SYMBOLS);
                format.setRoundingMode(RoundingMode.HALF_UP);
                return format;
            });

    private TimeFormats() {
    }

    /**
     * Renders seconds in the named style.
     *
     * @param seconds the time to render
     * @param style   how to render it
     * @return the rendered text
     */
    public static String render(double seconds, String style) {
        double safe = Double.isFinite(seconds) && seconds > 0 ? seconds : 0;

        return switch (style == null ? "" : style) {
            case "", "auto" -> auto(safe);
            case "seconds", "s" -> String.valueOf((long) Math.floor(safe));
            case "tenths", "1" -> ONE_DECIMAL.get().format(safe);
            case "hundredths", "2" -> TWO_DECIMALS.get().format(safe);
            case "clock" -> clock(safe);
            case "full" -> full(safe);
            default -> auto(safe);
        };
    }

    /**
     * Shows decimals only when they carry information.
     *
     * <p>Under ten seconds a tenth is worth showing, because that is when a
     * player is reacting to the number. Above that it is noise, and above a
     * minute a clock reads better than a large second count.
     */
    private static String auto(double seconds) {
        if (seconds < 10) {
            return ONE_DECIMAL.get().format(seconds);
        }
        if (seconds < 60) {
            return String.valueOf((long) Math.floor(seconds));
        }
        return clock(seconds);
    }

    /** Renders as {@code m:ss}, or {@code h:mm:ss} past an hour. */
    private static String clock(double seconds) {
        long whole = (long) Math.floor(seconds);
        long hours = whole / 3600;
        long minutes = whole % 3600 / 60;
        long remainder = whole % 60;

        if (hours > 0) {
            return hours + ":" + pad(minutes) + ":" + pad(remainder);
        }
        return minutes + ":" + pad(remainder);
    }

    /** Renders as {@code 1h 5m 3s}, for durations a player reads once. */
    private static String full(double seconds) {
        long whole = (long) Math.floor(seconds);
        long hours = whole / 3600;
        long minutes = whole % 3600 / 60;
        long remainder = whole % 60;

        StringBuilder result = new StringBuilder(16);
        if (hours > 0) {
            result.append(hours).append("h ");
        }
        if (minutes > 0) {
            result.append(minutes).append("m ");
        }
        if (result.isEmpty() || remainder > 0) {
            result.append(remainder).append('s');
        }
        return result.toString().trim();
    }

    private static String pad(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
