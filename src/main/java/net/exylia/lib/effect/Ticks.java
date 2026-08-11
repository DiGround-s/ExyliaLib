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
            return fallback;
        }

        return switch (unit) {
            case "", "s", "sec", "secs", "second", "seconds" -> fromSeconds(value);
            case "ms", "milli", "millis" -> fromMillis(Math.round(value));
            case "t", "tick", "ticks" -> Math.max(0, Math.round(value));
            case "m", "min", "mins", "minute", "minutes" -> fromSeconds(value * 60);
            case "h", "hour", "hours" -> fromSeconds(value * 3600);
            default -> fallback;
        };
    }
}
