package net.exylia.lib.placeholder.internal;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a resolved value into the text that appears in a message.
 *
 * <p>Formatting lives here rather than in every resolver, so a balance is
 * written {@code %balance:#,##0%} in the config instead of being formatted by
 * hand in Java. The server owner controls presentation; the plugin only supplies
 * the number.
 *
 * <p>Named formats cover what servers actually ask for, and anything else is
 * treated as a {@link DecimalFormat} pattern.
 */
public final class Formats {

    /**
     * Compiled patterns, cached because {@link DecimalFormat} is expensive to
     * build and the same handful of patterns repeat forever.
     *
     * <p>{@code DecimalFormat} is not thread safe, so each entry is a
     * {@link ThreadLocal}: shared compilation, isolated use. That matters here
     * because rendering happens on many threads at once.
     */
    private static final Map<String, ThreadLocal<DecimalFormat>> PATTERNS = new ConcurrentHashMap<>();

    /** Symbols fixed to a known locale so a server's default cannot change output. */
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);

    private static final String[] COMPACT_SUFFIXES = {"", "k", "M", "B", "T"};

    private Formats() {
    }

    /**
     * Renders a value, applying a format when one was requested.
     *
     * @param value  the value a resolver returned, never {@code null}
     * @param format the text after the colon in the placeholder, or {@code null}
     * @return the text to substitute
     */
    public static String apply(Object value, String format) {
        if (format == null || format.isEmpty()) {
            return plain(value);
        }

        return switch (format) {
            case "comma" -> number(value, "#,##0");
            case "compact", "short" -> compact(value);
            case "percent" -> number(value, "#,##0.#") + "%";
            case "upper" -> plain(value).toUpperCase(Locale.ROOT);
            case "lower" -> plain(value).toLowerCase(Locale.ROOT);
            case "yesno" -> truthy(value) ? "yes" : "no";
            case "time" -> duration(value);
            case "fixed1" -> number(value, "0.0");
            case "fixed2" -> number(value, "0.00");
            default -> number(value, format);
        };
    }

    /** Renders without any formatting, the common case. */
    private static String plain(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Double || value instanceof Float) {
            // Avoid the "3.0" that toString would give for a whole number.
            double number = ((Number) value).doubleValue();
            if (number == Math.rint(number) && !Double.isInfinite(number)) {
                return String.valueOf((long) number);
            }
        }
        if (value instanceof Enum<?> constant) {
            return constant.name().toLowerCase(Locale.ROOT);
        }
        return String.valueOf(value);
    }

    private static String number(Object value, String pattern) {
        if (!(value instanceof Number number)) {
            return plain(value);
        }
        if (!isNumericPattern(pattern)) {
            // DecimalFormat treats unknown characters as literal prefixes and
            // suffixes, so a typo like ":comm" would silently render "comm42"
            // instead of failing. A pattern with no digit placeholder is a
            // mistake, not a format.
            return plain(value);
        }
        try {
            return PATTERNS.computeIfAbsent(pattern,
                            key -> ThreadLocal.withInitial(() -> {
                                DecimalFormat format = new DecimalFormat(key, SYMBOLS);
                                // Half-even is the JDK default and would render
                                // 1.25 as "1.2", which reads as a bug to anyone
                                // looking at a balance.
                                format.setRoundingMode(java.math.RoundingMode.HALF_UP);
                                return format;
                            }))
                    .get()
                    .format(number.doubleValue());
        } catch (IllegalArgumentException ignored) {
            // A malformed pattern in a config must not break the message.
            return plain(value);
        }
    }

    /**
     * Returns whether text is a numeric pattern rather than a typo.
     *
     * <p>{@link DecimalFormat} accepts almost anything: unknown characters
     * become literal prefixes and suffixes, so {@code ":comm"} would render
     * {@code "comm42"} and {@code ":###bad###"} would render {@code "42bad"},
     * both silently. A pattern is only trusted when it contains a digit
     * placeholder and nothing that looks like prose.
     */
    private static boolean isNumericPattern(String pattern) {
        boolean hasDigitPlaceholder = false;
        for (int i = 0; i < pattern.length(); i++) {
            char character = pattern.charAt(i);
            if (character == '0' || character == '#') {
                hasDigitPlaceholder = true;
            } else if (Character.isLetter(character)) {
                return false;
            }
        }
        return hasDigitPlaceholder;
    }

    /** 1500 becomes 1.5k, 2400000 becomes 2.4M. */
    private static String compact(Object value) {
        if (!(value instanceof Number number)) {
            return plain(value);
        }
        double amount = number.doubleValue();
        boolean negative = amount < 0;
        amount = Math.abs(amount);

        int step = 0;
        while (amount >= 1000 && step < COMPACT_SUFFIXES.length - 1) {
            amount /= 1000;
            step++;
        }

        String rendered = amount >= 100 || amount == Math.rint(amount)
                ? String.valueOf((long) amount)
                : number(amount, "0.#");
        return (negative ? "-" : "") + rendered + COMPACT_SUFFIXES[step];
    }

    /** Seconds become a readable duration such as {@code 1h 5m} or {@code 42s}. */
    private static String duration(Object value) {
        if (!(value instanceof Number number)) {
            return plain(value);
        }
        long seconds = number.longValue();
        if (seconds < 0) {
            return plain(value);
        }
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3600;
        long minutes = seconds % 3600 / 60;
        long remainder = seconds % 60;

        StringBuilder result = new StringBuilder(16);
        if (days > 0) {
            result.append(days).append("d ");
        }
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

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
