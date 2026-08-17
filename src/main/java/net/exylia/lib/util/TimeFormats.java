package net.exylia.lib.util;

import org.jetbrains.annotations.NotNull;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.Locale;

/**
 * Renders an amount of time the way a player should read it.
 *
 * <pre>{@code
 * TimeFormats.render(3.34, TimeFormats.Style.TENTHS);  // "3.3"
 * TimeFormats.render(95.0, TimeFormats.Style.CLOCK);   // "1:35"
 * TimeFormats.render(3.34, TimeFormats.Style.AUTO);    // "3.3"
 * TimeFormats.render(3665, TimeFormats.Style.FULL);    // "1h 1m 5s"
 * }</pre>
 *
 * <p>One implementation for the whole library, so a cooldown, a boss bar
 * countdown and a {@code %time%} placeholder all render the same number the
 * same way. Formatting time is the sort of thing every plugin writes slightly
 * differently until one of them shows {@code 3,3} and another {@code 3.30}.
 *
 * <h2>Locale</h2>
 * Symbols are fixed to a known locale rather than the server's. A host in
 * Europe would otherwise render {@code 3,3s} while another renders
 * {@code 3.3s} from the same config, which is not something a config author
 * can see coming.
 *
 * <h2>Rounding</h2>
 * Half-up, not Java's default half-even. Half-even renders {@code 0.25} as
 * {@code 0.2}, which reads as a countdown stalling when it passes that point.
 *
 * <h2>Threading</h2>
 * Safe from any thread: the underlying formatters are not thread safe, so each
 * thread gets its own.
 *
 * @since 1.12.0
 */
public final class TimeFormats {

    private TimeFormats() {
        throw new AssertionError("No instances.");
    }

    /** How a length of time should be written. */
    public enum Style {

        /**
         * Decimals only where they carry information.
         *
         * <p>A tenth under ten seconds, because that is when a player is
         * reacting to the number; whole seconds up to a minute; a clock past
         * that, since a large second count is hard to read at a glance.
         */
        AUTO,

        /** Whole seconds, rounded down: {@code "3"}. */
        SECONDS,

        /** One decimal: {@code "3.3"}. */
        TENTHS,

        /** Two decimals: {@code "3.34"}. */
        HUNDREDTHS,

        /** {@code "1:35"}, or {@code "1:05:00"} past an hour. */
        CLOCK,

        /** {@code "1h 5m 3s"}, for a duration read once rather than watched. */
        FULL,

        /**
         * The largest unit that says something, and nothing else:
         * {@code "3d"}, {@code "2.5h"}, {@code "45s"}.
         *
         * <p>For a length of time that appears inside a sentence rather than as
         * the subject of the screen — how old a mail is, how long until a
         * posting expires, when a member was last seen. Those are read at a
         * glance in a lore line, where {@code "3d"} is the whole answer and
         * {@code "3d 4h 12m 6s"} is four numbers to skip past.
         *
         * <p>The units run to years, which the other styles do not reach:
         * {@code CLOCK} and {@code FULL} count hours upwards forever, so a
         * three-day gap reads as {@code "72h"}. That is correct and unreadable.
         *
         * <p>A month is thirty days and a year is three hundred and sixty-five,
         * as ExyliaCommons had it. Both are approximations, which is the point:
         * a line that says {@code "2mo ago"} is not claiming to know which
         * months.
         *
         * @since 1.25.0
         */
        COMPACT
    }

    /**
     * The units {@link Style#COMPACT} counts in, largest first, in seconds.
     *
     * <p>Exactly ExyliaCommons' ladder, so a duration that read {@code "3d"}
     * before a plugin migrated still reads {@code "3d"} after.
     */
    private static final long[] COMPACT_UNITS = {
            31_536_000L,  // year, 365 days
            2_592_000L,   // month, 30 days
            604_800L,     // week
            86_400L,      // day
            3_600L,       // hour
            60L,          // minute
            1L};          // second

    /** Each unit's suffix, parallel to {@link #COMPACT_UNITS}. */
    private static final String[] COMPACT_SUFFIXES =
            {"y", "mo", "w", "d", "h", "m", "s"};

    private static final DecimalFormatSymbols SYMBOLS =
            DecimalFormatSymbols.getInstance(Locale.US);

    private static final ThreadLocal<DecimalFormat> ONE_DECIMAL =
            ThreadLocal.withInitial(() -> decimalFormat("0.0"));

    private static final ThreadLocal<DecimalFormat> TWO_DECIMALS =
            ThreadLocal.withInitial(() -> decimalFormat("0.00"));

    private static DecimalFormat decimalFormat(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, SYMBOLS);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    /**
     * Renders a number of seconds.
     *
     * <p>Anything negative or not a number renders as zero: a countdown that
     * has run out reads {@code "0.0"}, never {@code "-1.2"}.
     *
     * @param seconds the time to render
     * @param style   how to write it
     * @return the rendered text
     */
    public static @NotNull String render(double seconds, @NotNull Style style) {
        double safe = Double.isFinite(seconds) && seconds > 0 ? seconds : 0;
        return switch (style) {
            case AUTO -> auto(safe);
            case SECONDS -> String.valueOf((long) Math.floor(safe));
            case TENTHS -> ONE_DECIMAL.get().format(safe);
            case HUNDREDTHS -> TWO_DECIMALS.get().format(safe);
            case CLOCK -> clock(safe);
            case FULL -> full(safe);
            case COMPACT -> compact(safe);
        };
    }

    /** Renders a duration. */
    public static @NotNull String render(@NotNull Duration duration, @NotNull Style style) {
        return render(duration.toMillis() / 1000.0, style);
    }

    /** Renders a number of seconds in the {@link Style#AUTO} style. */
    public static @NotNull String render(double seconds) {
        return render(seconds, Style.AUTO);
    }

    /**
     * Renders a number of seconds in a style named by a config.
     *
     * <p>Names are the lowercase enum constants, plus the shorthands a config
     * author reaches for: {@code "s"}, {@code "1"} and {@code "2"}. An
     * unknown name falls back to {@link Style#AUTO} rather than failing,
     * because a typo in a config should not stop a boss bar from drawing.
     *
     * @param seconds the time to render
     * @param style   the style's name, or {@code null} for {@link Style#AUTO}
     * @return the rendered text
     */
    public static @NotNull String render(double seconds, String style) {
        return render(seconds, styleOf(style));
    }

    /**
     * Reads a style's name, falling back to {@link Style#AUTO}.
     *
     * @param name the name, or {@code null}
     * @return the style
     */
    public static @NotNull Style styleOf(String name) {
        if (name == null || name.isEmpty()) {
            return Style.AUTO;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "seconds", "s" -> Style.SECONDS;
            case "tenths", "1" -> Style.TENTHS;
            case "hundredths", "2" -> Style.HUNDREDTHS;
            case "clock" -> Style.CLOCK;
            case "full" -> Style.FULL;
            case "compact" -> Style.COMPACT;
            default -> Style.AUTO;
        };
    }

    private static String auto(double seconds) {
        if (seconds < 10) {
            return ONE_DECIMAL.get().format(seconds);
        }
        if (seconds < 60) {
            return String.valueOf((long) Math.floor(seconds));
        }
        return clock(seconds);
    }

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

    /**
     * The largest unit that says something, with a tenth only where it does.
     *
     * <p>{@code 259200} is {@code "3d"} rather than {@code "3.0d"}, and
     * {@code 9000} is {@code "2.5h"} rather than {@code "2h"} — a lore line
     * that rounds two and a half hours down to two is telling a player they
     * have half an hour they do not have.
     *
     * <p>Below a second it is {@code "0s"}, not milliseconds. Nothing reading
     * this style cares about a gap that small; the styles that do are the
     * decimal ones above.
     */
    private static String compact(double seconds) {
        for (int index = 0; index < COMPACT_UNITS.length; index++) {
            long unit = COMPACT_UNITS[index];
            if (seconds >= unit) {
                double scaled = seconds / unit;
                // Rounded half-up by hand rather than through the formatter,
                // because the decimal is dropped more often than it is kept and
                // formatting it only to strip it is work for nothing.
                double rounded = Math.floor(scaled * 10 + 0.5) / 10;
                long whole = (long) rounded;
                int tenth = (int) Math.round((rounded - whole) * 10);
                String suffix = COMPACT_SUFFIXES[index];
                return tenth == 0 ? whole + suffix : whole + "." + tenth + suffix;
            }
        }
        return "0s";
    }

    private static String pad(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
