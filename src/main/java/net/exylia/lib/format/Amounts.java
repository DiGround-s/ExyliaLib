package net.exylia.lib.format;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Reads an amount the way a player types it.
 *
 * <pre>{@code
 * Amounts.parse("10M");     // 10000000
 * Amounts.parse("1.5k");    // 1500
 * Amounts.parse("2,500");   // 2500
 * Amounts.parse("1,5");     // empty — ambiguous, see below
 * }</pre>
 *
 * <p>What {@code /pay <player> 10M} needs. Written once here rather than in
 * every command that takes a number, because the interesting part is not the
 * suffixes — it is deciding what to refuse.
 *
 * <h2>Why a BigDecimal</h2>
 * A balance is the one number a {@code double} must not hold. Parsing
 * {@code "0.1"} into a {@code double} and adding it to a balance three times
 * does not produce {@code 0.3}, and a player counting coins notices. The caller
 * decides what to do with the value; this only refuses to lose precision on the
 * way in.
 *
 * <h2>What is refused, and why</h2>
 * {@code "1,5"} is fifteen tenths in most of Europe and fifteen with a stray
 * separator elsewhere. There is no reading that is right for both, so it is
 * refused rather than guessed: a transfer command that silently turns
 * {@code 1,5} into {@code 15} is a bug report about stolen money.
 *
 * <p>{@code "1,500"} is unambiguous — a comma with exactly three digits after
 * it is a thousands separator in both conventions — and is accepted.
 *
 * @since 1.25.0
 */
public final class Amounts {

    private Amounts() {
        throw new AssertionError("No instances.");
    }

    /** The multiplier each suffix stands for. */
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal BILLION = BigDecimal.valueOf(1_000_000_000L);
    private static final BigDecimal TRILLION = BigDecimal.valueOf(1_000_000_000_000L);
    private static final BigDecimal QUADRILLION = BigDecimal.valueOf(1_000_000_000_000_000L);

    /**
     * Reads an amount.
     *
     * <p>Accepts a plain number, one with a {@code K}, {@code M}, {@code B},
     * {@code T} or {@code Q} suffix in either case, underscores as separators,
     * and commas grouping thousands. Anything else — including an ambiguous
     * separator, an unknown suffix, or a negative amount — is empty.
     *
     * <p>An amount is money, and money is never negative here. A setting that
     * uses {@code -1} as a sentinel wants {@link #parseSigned(String)}.
     *
     * @param input what the player typed
     * @return the amount, or empty when it cannot be read unambiguously
     */
    public static @NotNull Optional<BigDecimal> parse(String input) {
        return parseSigned(input).filter(value -> value.signum() >= 0);
    }

    /**
     * Reads a number, negative or not.
     *
     * <p>The same reading as {@link #parse(String)} without the money rule, for
     * a field where a negative is a real answer: {@code -1} for unlimited, a
     * sort order that puts something first, an offset.
     *
     * @param input what the player typed
     * @return the number, or empty when it cannot be read unambiguously
     */
    public static @NotNull Optional<BigDecimal> parseSigned(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal multiplier = BigDecimal.ONE;
        char last = text.charAt(text.length() - 1);
        BigDecimal suffix = multiplierOf(last);
        if (suffix != null) {
            multiplier = suffix;
            text = text.substring(0, text.length() - 1).trim();
            if (text.isEmpty()) {
                return Optional.empty();
            }
        }

        String digits = withoutSeparators(text);
        if (digits == null) {
            return Optional.empty();
        }
        try {
            BigDecimal value = new BigDecimal(digits);
            return Optional.of(multiplier.equals(BigDecimal.ONE)
                    ? value
                    : value.multiply(multiplier).stripTrailingZeros());
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /**
     * Reads an amount, or a fallback.
     *
     * @param input    what the player typed
     * @param fallback what to use when it cannot be read
     * @return the amount
     */
    public static @NotNull BigDecimal parseOr(String input, @NotNull BigDecimal fallback) {
        return parse(input).orElse(fallback);
    }

    /**
     * Reads a whole amount, for something that cannot be fractional.
     *
     * <p>An item count, a level, a number of kills. A fractional input is
     * refused rather than truncated: somebody typing {@code 1.5} meant
     * something, and giving them one is not it.
     *
     * @param input what the player typed
     * @return the amount, or empty when it cannot be read or is not whole
     */
    public static @NotNull Optional<Long> parseWhole(String input) {
        return whole(parse(input));
    }

    /**
     * Reads a whole number, negative or not.
     *
     * @param input what the player typed
     * @return the number, or empty when it cannot be read or is not whole
     */
    public static @NotNull Optional<Long> parseSignedWhole(String input) {
        return whole(parseSigned(input));
    }

    private static Optional<Long> whole(Optional<BigDecimal> parsed) {
        return parsed.flatMap(value -> {
            try {
                return Optional.of(value.longValueExact());
            } catch (ArithmeticException notWholeOrTooLarge) {
                return Optional.empty();
            }
        });
    }

    private static BigDecimal multiplierOf(char suffix) {
        return switch (suffix) {
            case 'k', 'K' -> THOUSAND;
            case 'm', 'M' -> MILLION;
            case 'b', 'B' -> BILLION;
            case 't', 'T' -> TRILLION;
            case 'q', 'Q' -> QUADRILLION;
            default -> null;
        };
    }

    /**
     * Strips the separators a player might type, or refuses the string.
     *
     * <p>An underscore is only ever a separator, so it is dropped wherever it
     * appears. A comma is a separator in one convention and a decimal point in
     * another, so it is only dropped where the reading is the same either way:
     * followed by exactly three digits, and not alongside a dot.
     *
     * @return the digits, or {@code null} when the string cannot be read
     *         unambiguously
     */
    private static String withoutSeparators(String text) {
        if (text.indexOf('_') >= 0) {
            text = text.replace("_", "");
        }
        int comma = text.indexOf(',');
        if (comma < 0) {
            return text;
        }
        // A comma and a dot together is a full grouped number in one convention
        // and nonsense in the other. Refusing it costs nobody anything: a player
        // typing a price does not write "1,234.56".
        if (text.indexOf('.') >= 0) {
            return null;
        }
        for (int index = comma; index >= 0; index = text.indexOf(',', index + 1)) {
            // Three digits after a comma, and either the end or another comma.
            int next = index + 4;
            if (next > text.length()) {
                return null;
            }
            for (int digit = index + 1; digit < next; digit++) {
                if (!Character.isDigit(text.charAt(digit))) {
                    return null;
                }
            }
            if (next < text.length() && text.charAt(next) != ',') {
                return null;
            }
        }
        return text.replace(",", "");
    }
}
