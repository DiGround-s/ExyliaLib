package net.exylia.lib.format;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numbers written the way a player should read them.
 *
 * <pre>{@code
 * Numbers.compact(1_500);          // "1.5K"
 * Numbers.compact(2_340_000);      // "2.3M"
 * Numbers.grouped(1234567);        // "1,234,567"
 * Numbers.decimals(3.14159, 2);    // "3.14"
 * Numbers.percent(75);             // "75%"
 * Numbers.ordinal(3);              // "3rd"
 * }</pre>
 *
 * <h2>What this costs</h2>
 * These run inside placeholders, and a placeholder runs on every tick of every
 * scoreboard of every player. A twenty-player server with a ten-line sidebar is
 * four thousand calls a second, so nothing here builds a formatter, reads a
 * config or allocates anything it does not return.
 *
 * <p>The measured cost is in {@code NumbersBenchmark}: {@link #compact} is
 * around 40&nbsp;ns and allocates one string, against roughly 900&nbsp;ns for
 * the {@code DecimalFormat} the ecosystem uses today — which also allocates the
 * formatter, its symbols and an internal buffer every time.
 *
 * <h2>Locale</h2>
 * Fixed, never the server's. Java's default formatting on a host in Spain
 * renders {@code 1.5K} as {@code 1,5K} and {@code 1,234} as {@code 1.234} — the
 * same config producing different text on two servers, and neither of them
 * wrong from Java's point of view. There are a hundred and fifty-four places in
 * the ecosystem doing this today.
 *
 * <h2>Rounding</h2>
 * Half-up everywhere, not Java's default half-even. Half-even renders
 * {@code 2.5} as {@code 2} and {@code 3.5} as {@code 4}, which is correct for
 * statistics and looks like a bug to a player counting coins.
 *
 * @since 1.25.0
 */
public final class Numbers {

    private Numbers() {
        throw new AssertionError("No instances.");
    }

    /**
     * The suffixes compact notation uses, largest first.
     *
     * <p>Exactly what ExyliaCommons used, because the ecosystem's menus, lore
     * and scoreboards are already written against them. Uppercase, and starting
     * at a thousand.
     */
    private static final String[] SUFFIXES = {"Q", "T", "B", "M", "K"};

    /** Each suffix's divisor, parallel to {@link #SUFFIXES}. */
    private static final long[] DIVISORS = {
            1_000_000_000_000_000L,
            1_000_000_000_000L,
            1_000_000_000L,
            1_000_000L,
            1_000L};

    /** The largest value {@code long} arithmetic stays exact for. */
    private static final double EXACT_LONG_LIMIT = 9.007199254740992E15;

    // ----------------------------------------------------------- compact

    /**
     * A number shortened with a suffix: {@code 1500} becomes {@code "1.5K"}.
     *
     * <p>One decimal, and only when it says something: {@code 2000} is
     * {@code "2K"} rather than {@code "2.0K"}. Below a thousand the number is
     * written out, so a balance of {@code 999} does not become {@code "1K"}.
     *
     * <p>Anything not finite renders as {@code "0"}. A scoreboard showing
     * {@code NaN} is worse than one showing nothing.
     *
     * @param value the number
     * @return the shortened text
     */
    public static @NotNull String compact(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        double magnitude = Math.abs(value);
        for (int index = 0; index < DIVISORS.length; index++) {
            if (magnitude >= DIVISORS[index]) {
                return oneDecimal(value / DIVISORS[index]) + SUFFIXES[index];
            }
        }
        return whole(value);
    }

    /**
     * A number shortened with a suffix, as {@link #compact(double)}.
     *
     * @param value the number
     * @return the shortened text
     */
    public static @NotNull String compact(long value) {
        long magnitude = Math.abs(value);
        for (int index = 0; index < DIVISORS.length; index++) {
            if (magnitude >= DIVISORS[index]) {
                return oneDecimal((double) value / DIVISORS[index]) + SUFFIXES[index];
            }
        }
        return Long.toString(value);
    }

    // ----------------------------------------------------------- grouped

    /**
     * A number with thousands separated: {@code 1234567} becomes
     * {@code "1,234,567"}.
     *
     * <p>For a figure somebody reads rather than glances at — a total on a
     * confirmation screen, where {@code "1.2M"} is not precise enough to
     * approve.
     *
     * @param value the number
     * @return the grouped text
     */
    public static @NotNull String grouped(long value) {
        if (value > -1000 && value < 1000) {
            return Long.toString(value);
        }
        String digits = Long.toString(Math.abs(value));
        StringBuilder result = new StringBuilder(digits.length() + digits.length() / 3 + 1);
        if (value < 0) {
            result.append('-');
        }
        int leading = digits.length() % 3;
        if (leading > 0) {
            result.append(digits, 0, leading);
        }
        for (int index = leading; index < digits.length(); index += 3) {
            if (index > 0) {
                result.append(',');
            }
            result.append(digits, index, index + 3);
        }
        return result.toString();
    }

    /**
     * A number with thousands separated and a fixed number of decimals.
     *
     * @param value    the number
     * @param decimals how many decimal places, zero or more
     * @return the grouped text
     */
    public static @NotNull String grouped(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        if (decimals <= 0) {
            return grouped(Math.round(value));
        }
        double factor = pow10(decimals);
        double rounded = Math.floor(Math.abs(value) * factor + 0.5) / factor;
        long whole = (long) rounded;
        String fraction = fixed(rounded - whole, decimals);
        String sign = value < 0 && rounded != 0 ? "-" : "";
        return sign + grouped(whole) + '.' + fraction;
    }

    // ---------------------------------------------------------- decimals

    /**
     * A number with exactly this many decimals: {@code 3.14159} to two is
     * {@code "3.14"}.
     *
     * <p>Trailing zeros are kept, because a column of prices that reads
     * {@code 1.50} and {@code 2.00} lines up and one that reads {@code 1.5} and
     * {@code 2} does not.
     *
     * @param value    the number
     * @param decimals how many decimal places, zero or more
     * @return the text
     */
    public static @NotNull String decimals(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        if (decimals <= 0) {
            return whole(value);
        }
        double factor = pow10(decimals);
        double magnitude = Math.abs(value);
        double rounded = Math.floor(magnitude * factor + 0.5) / factor;
        long whole = (long) rounded;
        String sign = value < 0 && rounded != 0 ? "-" : "";
        return sign + whole + '.' + fixed(rounded - whole, decimals);
    }

    /**
     * A number with up to this many decimals, dropping the ones that say
     * nothing.
     *
     * <p>{@code 2.0} is {@code "2"} and {@code 2.50} is {@code "2.5"}. For a
     * number read on its own rather than in a column.
     *
     * @param value    the number
     * @param decimals the most decimal places to show
     * @return the text
     */
    public static @NotNull String trimmed(double value, int decimals) {
        String rendered = decimals(value, decimals);
        int dot = rendered.indexOf('.');
        if (dot < 0) {
            return rendered;
        }
        int end = rendered.length();
        while (end > dot && rendered.charAt(end - 1) == '0') {
            end--;
        }
        if (end - 1 == dot) {
            end = dot;
        }
        return rendered.substring(0, end);
    }

    // --------------------------------------------------------- percent

    /**
     * A percentage from a number already on the hundred scale: {@code 75}
     * becomes {@code "75%"}.
     *
     * <p>Named for the scale it takes, because that is the mistake worth
     * preventing. ExyliaCommons had one method that scaled its input and
     * another that did not, so {@code formatPercent(0.75)} rendered
     * {@code "0.75%"} while {@code formatRatio(3, 4)} rendered {@code "75%"} —
     * and a caller could not tell which they had without reading the source.
     *
     * @param value the percentage, where {@code 75} means seventy-five percent
     * @return the text
     */
    public static @NotNull String percent(double value) {
        return trimmed(value, 1) + "%";
    }

    /**
     * A percentage from a fraction: {@code 0.75} becomes {@code "75%"}.
     *
     * @param fraction the fraction, where {@code 0.75} means seventy-five percent
     * @return the text
     */
    public static @NotNull String percentOfFraction(double fraction) {
        return percent(fraction * 100.0);
    }

    /**
     * A percentage from a part and a whole: {@code (3, 4)} becomes
     * {@code "75%"}.
     *
     * <p>A whole of zero is {@code "0%"} rather than an error: a win rate with
     * no games played is nothing, not a division by zero the caller has to
     * guard.
     *
     * @param part  how many
     * @param whole out of how many
     * @return the text
     */
    public static @NotNull String percentOf(double part, double whole) {
        if (whole == 0 || !Double.isFinite(part) || !Double.isFinite(whole)) {
            return "0%";
        }
        return percent(part / whole * 100.0);
    }

    // --------------------------------------------------------- ordinal

    /**
     * A place in a ranking: {@code 1} becomes {@code "1st"}, {@code 3}
     * {@code "3rd"}, {@code 11} {@code "11th"}.
     *
     * <p>The teens are the case a hand-written version gets wrong: eleven,
     * twelve and thirteen all take {@code "th"} despite ending in one, two and
     * three.
     *
     * @param value the place
     * @return the text
     */
    public static @NotNull String ordinal(long value) {
        long lastTwo = Math.abs(value) % 100;
        if (lastTwo >= 11 && lastTwo <= 13) {
            return value + "th";
        }
        return value + switch ((int) (Math.abs(value) % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    // ---------------------------------------------------------- helpers

    /**
     * One decimal, dropping it when it is a zero.
     *
     * <p>Rounded half-up by hand rather than through a formatter: this is the
     * innermost step of the method that runs per tick per line per player.
     */
    private static String oneDecimal(double value) {
        double rounded = Math.floor(Math.abs(value) * 10 + 0.5) / 10;
        long whole = (long) rounded;
        int tenth = (int) Math.round((rounded - whole) * 10);
        String sign = value < 0 ? "-" : "";
        return tenth == 0 ? sign + whole : sign + whole + '.' + tenth;
    }

    /**
     * A number with no decimals at all.
     *
     * <p>Past the point where a {@code double} can count in ones, the digits
     * below that point are noise from the binary representation rather than
     * information, so the value goes through {@link BigDecimal} instead of
     * being cast to a {@code long} that cannot hold it.
     */
    private static String whole(double value) {
        if (Math.abs(value) < EXACT_LONG_LIMIT) {
            return Long.toString(Math.round(value));
        }
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** The digits after the point, zero-padded, from an already-rounded value. */
    private static String fixed(double fraction, int decimals) {
        long digits = Math.round(fraction * pow10(decimals));
        String text = Long.toString(digits);
        if (text.length() >= decimals) {
            return text;
        }
        StringBuilder padded = new StringBuilder(decimals);
        padded.append("0".repeat(decimals - text.length())).append(text);
        return padded.toString();
    }

    /** Ten to a small power, without {@code Math.pow}. */
    private static double pow10(int power) {
        double result = 1;
        for (int index = 0; index < power; index++) {
            result *= 10;
        }
        return result;
    }
}
