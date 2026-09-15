package net.exylia.lib.effect;

/**
 * Converts between the units a server owner writes and the ticks the server
 * counts in.
 *
 * <p>A tick is 50ms, so a duration written as {@code 3.3s} is 66 ticks. Every
 * effect in this module accepts seconds as a decimal for exactly that reason: a
 * countdown that shows {@code 3.3s} has to be driven by something finer than a
 * whole second, and asking plugin authors to pre-multiply by 20 is how off-by-one
 * timers get written.
 *
 * <p>Rounding is to the nearest tick rather than truncating: {@code 0.09s} is
 * closer to two ticks than to one, and truncating would make a short effect
 * vanish entirely.
 *
 * @since 1.4.0
 */
public final class Ticks {

    /** How long one tick lasts, in milliseconds. */
    public static final long MILLIS = 50L;

    /** Ticks in one second. */
    public static final int PER_SECOND = 20;

    private Ticks() {
        throw new AssertionError("No instances.");
    }

    /**
     * Converts seconds to ticks.
     *
     * @param seconds the duration in seconds, decimals allowed
     * @return the duration in ticks, never negative
     */
    public static long fromSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) {
            return 0;
        }
        return Math.round(seconds * PER_SECOND);
    }

    /**
     * Converts ticks to seconds.
     *
     * @param ticks the duration in ticks
     * @return the duration in seconds, with decimals
     */
    public static double toSeconds(long ticks) {
        return ticks / (double) PER_SECOND;
    }

    /**
     * Converts milliseconds to ticks.
     *
     * @param millis the duration in milliseconds
     * @return the duration in ticks, never negative
     */
    public static long fromMillis(long millis) {
        return millis <= 0 ? 0 : Math.round(millis / (double) MILLIS);
    }

    /**
     * Converts ticks to milliseconds.
     *
     * @param ticks the duration in ticks
     * @return the duration in milliseconds
     */
    public static long toMillis(long ticks) {
        return ticks * MILLIS;
    }

    /**
     * Parses a duration written the way a server owner writes one.
     *
     * <p>Accepts a bare number as seconds, or a number with a unit:
     * {@code 3.3s}, {@code 500ms}, {@code 2m}, {@code 1h}, {@code 40t}. Ticks
     * are spelled {@code t} because that is the only unit the server counts in
     * exactly.
     *
     * <p>Since 1.163.0 anything {@link net.exylia.lib.input.InputParser#duration()}
     * reads is read too: days, weeks, months, years and several parts at once,
     * {@code 7d12h30m}. A caller that must tell the owner <em>why</em> a value
     * was refused asks that parser instead, which answers with the reason
     * rather than a fallback.
     *
     * @param text     the duration as written
     * @param fallback returned when the text cannot be read
     * @return the duration in ticks
     */
    public static long parse(String text, long fallback) {
        if (text == null) {
            return fallback;
        }
        String trimmed = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty()) {
            return fallback;
        }

        int end = trimmed.length();
        while (end > 0 && !Character.isDigit(trimmed.charAt(end - 1)) && trimmed.charAt(end - 1) != '.') {
            end--;
        }

        String number = trimmed.substring(0, end);
        String unit = trimmed.substring(end);

        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException ignored) {
            return compound(trimmed, fallback);
        }

        return switch (unit) {
            case "", "s", "sec", "secs", "second", "seconds" -> fromSeconds(value);
            case "ms", "milli", "millis" -> fromMillis(Math.round(value));
            case "t", "tick", "ticks" -> Math.max(0, Math.round(value));
            case "m", "min", "mins", "minute", "minutes" -> fromSeconds(value * 60);
            case "h", "hour", "hours" -> fromSeconds(value * 3600);
            default -> compound(trimmed, fallback);
        };
    }

    /**
     * The same, for a value whose bare number has always meant ticks.
     *
     * <p>{@code refresh.interval: 20} in a hundred and sixty-one deployed menus
     * means twenty ticks, and it has to keep meaning that. A number with a unit
     * on it is read the way it is written, so the same key also accepts
     * {@code 1s} and {@code 1m30s} — the owner says which they meant.
     *
     * @param text     the duration as written
     * @param fallback returned in ticks when the text cannot be read
     * @return the duration in ticks
     * @since 1.171.0
     */
    public static long parseTicks(String text, long fallback) {
        if (text == null) {
            return fallback;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        // A bare number is the only case that differs from parse(): it is this
        // key's own unit, not seconds.
        try {
            return Math.max(0, Math.round(Double.parseDouble(trimmed)));
        } catch (NumberFormatException written) {
            return parse(trimmed, fallback);
        }
    }

    /**
     * A duration in seconds, the unit most of the library's files are written in.
     *
     * <p>A bare number is seconds, as it always was; anything else is read by
     * {@link #parse}. Decimals survive, because half a second of a title fading
     * up is a real setting.
     *
     * @param text     the duration as written
     * @param fallback returned in seconds when the text cannot be read
     * @return the duration in seconds
     * @since 1.171.0
     */
    public static double parseSeconds(String text, double fallback) {
        if (text == null) {
            return fallback;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException written) {
            long ticks = parse(trimmed, Long.MIN_VALUE);
            return ticks == Long.MIN_VALUE ? fallback : toSeconds(ticks);
        }
    }

    /**
     * Writes a length of time the way these parsers read it back.
     *
     * <p>{@code 90000} is {@code "1m30s"}: no spaces, because a value written
     * into a config file, a sequence line or an editor's box has to survive
     * being read again, and every parser here stops at the first space.
     *
     * <p>{@link net.exylia.lib.util.TimeFormats} is what a <em>player</em>
     * reads; this is what a file holds.
     *
     * @param millis the duration in milliseconds
     * @return the duration as written, never blank
     * @since 1.171.0
     */
    public static String write(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        StringBuilder written = new StringBuilder();
        long left = millis;
        for (Unit unit : Unit.values()) {
            long whole = left / unit.millis;
            if (whole > 0) {
                written.append(whole).append(unit.suffix);
                left -= whole * unit.millis;
            }
        }
        // Under a millisecond, which no config means and no parser would read.
        return written.isEmpty() ? "0s" : written.toString();
    }

    /** The units {@link #write} uses, largest first. */
    private enum Unit {
        DAYS(86_400_000L, "d"),
        HOURS(3_600_000L, "h"),
        MINUTES(60_000L, "m"),
        SECONDS(1_000L, "s"),
        MILLIS(1L, "ms");

        private final long millis;
        private final String suffix;

        Unit(long millis, String suffix) {
            this.millis = millis;
            this.suffix = suffix;
        }
    }

    /** What the single-unit reading refused, through the one parser that reads every unit. */
    private static long compound(String text, long fallback) {
        return net.exylia.lib.input.InputParser.duration().parse(text).optional()
                .map(duration -> fromMillis(duration.toMillis()))
                .orElse(fallback);
    }
}
