package net.exylia.lib.input;

import net.exylia.lib.format.Amounts;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a player typed into the type that was asked for.
 *
 * <p>One implementation per type, used by every way of asking. ExyliaCommons
 * parsed and validated separately inside the chat handler, the Floodgate handler
 * and the dialog handler, so the same text could be accepted in one and rejected
 * in another — and a fix to the range check in one of them left the other two
 * wrong. Here the transports collect raw strings and nothing else; this is where
 * text becomes a value.
 *
 * <p>The practical consequence: {@code 10M} means ten million whether it was
 * typed into a dialog box, into chat, or into a Bedrock form.
 *
 * @param <T> the type produced
 * @since 1.31.0
 */
@FunctionalInterface
public interface InputParser<T> {

    /**
     * Reads a value.
     *
     * @param raw what the player typed, already trimmed
     * @return the value, or a rejection carrying the reason
     */
    @NotNull Parsed<T> parse(@NotNull String raw);

    /**
     * The outcome of one parse.
     *
     * @param value the value, or {@code null} when rejected
     * @param error why it was rejected, or {@code null} when accepted
     * @param <T>   the type produced
     * @since 1.31.0
     */
    record Parsed<T>(@Nullable T value, @Nullable String error) {

        /**
         * An accepted value.
         *
         * @param value the value
         * @param <T>   the type produced
         * @return the outcome
         */
        public static <T> @NotNull Parsed<T> of(@NotNull T value) {
            return new Parsed<>(value, null);
        }

        /**
         * A rejection.
         *
         * @param message what the player reads
         * @param <T>     the type produced
         * @return the outcome
         */
        public static <T> @NotNull Parsed<T> rejected(@NotNull String message) {
            return new Parsed<>(null, message);
        }

        /** Whether a value was produced. */
        public boolean ok() {
            return value != null;
        }

        /** The value as an optional. */
        public @NotNull Optional<T> optional() {
            return Optional.ofNullable(value);
        }
    }

    // ------------------------------------------------------------ built-ins

    /** Anything at all, unchanged. */
    static @NotNull InputParser<String> text() {
        return Parsers.TEXT;
    }

    /**
     * A whole number.
     *
     * <p>Accepts the suffixes and separators a player types, so
     * {@code 10k} is ten thousand here exactly as it is in a {@code /pay}
     * command. A fractional answer is refused rather than truncated: somebody
     * typing {@code 1.5} for a slot count meant something, and giving them one
     * is not it.
     */
    static @NotNull InputParser<Long> integer() {
        return Parsers.INTEGER;
    }

    /**
     * An exact decimal.
     *
     * <p>A {@link BigDecimal}, never a {@code double}. This is what a price or
     * a multiplier is read into, and {@code 0.1 + 0.2} in a double is
     * {@code 0.30000000000000004}.
     */
    static @NotNull InputParser<BigDecimal> decimal() {
        return Parsers.DECIMAL;
    }

    /**
     * An amount of money.
     *
     * <p>The same reader a {@code /pay} command uses: {@code 10M},
     * {@code 1.5k} and {@code 2,500} are accepted, and genuinely ambiguous
     * input such as {@code 1,5} is refused rather than guessed.
     */
    static @NotNull InputParser<BigDecimal> amount() {
        return Parsers.AMOUNT;
    }

    /**
     * A yes or no.
     *
     * <p>Generous about what a player types, because this is the one field
     * where being strict achieves nothing: nobody typing {@code y} meant no.
     */
    static @NotNull InputParser<Boolean> flag() {
        return Parsers.FLAG;
    }

    /**
     * A length of time: {@code 30s}, {@code 5m}, {@code 1h30m}, {@code 2d}.
     *
     * <p>The counterpart to {@code TimeFormats}, which writes durations. A
     * plain number is read as seconds, because that is what somebody typing
     * {@code 30} into a cooldown box means.
     */
    static @NotNull InputParser<Duration> duration() {
        return Parsers.DURATION;
    }

    /**
     * A lowercase identifier, refusing anything that is not one.
     *
     * <p>Spaces become underscores and case is folded, because those are
     * typing conventions rather than mistakes. A stray {@code !} is a mistake
     * and is reported: silently turning {@code arena!} into {@code arena} is
     * how somebody creates a second arena they cannot tell from the first.
     */
    static @NotNull InputParser<String> id() {
        return Parsers.ID;
    }

    /**
     * A lowercase identifier, dropping anything that is not one.
     *
     * <p>For deriving an id from a display name, where the caller has already
     * decided that whatever survives is the answer.
     */
    static @NotNull InputParser<String> slug() {
        return Parsers.SLUG;
    }

    /**
     * The implementations.
     *
     * <p>Constants rather than lambdas returned per call: a parser is on the
     * path of every answer, and there is no state to give one.
     */
    final class Parsers {

        private Parsers() {
        }

        static final InputParser<String> TEXT = Parsed::of;

        static final InputParser<Long> INTEGER = raw -> {
            Optional<Long> parsed = Amounts.parseWhole(raw);
            if (parsed.isPresent()) {
                return Parsed.of(parsed.get());
            }
            // Told apart so the message is useful: "not a number" and "not a
            // whole number" send a player to two different corrections.
            if (Amounts.parse(raw).isPresent()) {
                return Parsed.rejected("Enter a whole number.");
            }
            return Parsed.rejected("Enter a number.");
        };

        static final InputParser<BigDecimal> DECIMAL = raw -> Amounts.parse(raw)
                .<Parsed<BigDecimal>>map(Parsed::of)
                .orElseGet(() -> Parsed.rejected("Enter a number."));

        static final InputParser<BigDecimal> AMOUNT = raw -> Amounts.parse(raw)
                .<Parsed<BigDecimal>>map(Parsed::of)
                .orElseGet(() -> Parsed.rejected("Enter an amount, such as 100 or 10M."));

        static final InputParser<Boolean> FLAG = raw -> switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "on", "1", "enable", "enabled", "si", "sí" -> Parsed.of(Boolean.TRUE);
            case "false", "no", "n", "off", "0", "disable", "disabled" -> Parsed.of(Boolean.FALSE);
            default -> Parsed.rejected("Answer yes or no.");
        };

        private static final Pattern DURATION_PART =
                Pattern.compile("(\\d+)\\s*(d|h|m|s|ms)", Pattern.CASE_INSENSITIVE);

        static final InputParser<Duration> DURATION = raw -> {
            String text = raw.trim().toLowerCase(Locale.ROOT);
            if (text.isEmpty()) {
                return Parsed.rejected("Enter a duration, such as 30s or 1h30m.");
            }
            // A bare number is seconds: it is what somebody typing 30 into a
            // cooldown box means, and refusing it would be pedantry.
            if (text.chars().allMatch(Character::isDigit)) {
                try {
                    return Parsed.of(Duration.ofSeconds(Long.parseLong(text)));
                } catch (NumberFormatException tooLarge) {
                    return Parsed.rejected("That duration is too large.");
                }
            }

            Matcher matcher = DURATION_PART.matcher(text);
            Duration total = Duration.ZERO;
            int consumed = 0;
            boolean any = false;
            while (matcher.find()) {
                if (matcher.start() != consumed) {
                    // Something between the parts that is not a unit, so the
                    // whole string is rejected rather than half-read.
                    return Parsed.rejected("Enter a duration, such as 30s or 1h30m.");
                }
                consumed = matcher.end();
                long amount;
                try {
                    amount = Long.parseLong(matcher.group(1));
                } catch (NumberFormatException tooLarge) {
                    return Parsed.rejected("That duration is too large.");
                }
                total = switch (matcher.group(2)) {
                    case "d" -> total.plusDays(amount);
                    case "h" -> total.plusHours(amount);
                    case "m" -> total.plusMinutes(amount);
                    case "s" -> total.plusSeconds(amount);
                    default -> total.plusMillis(amount);
                };
                any = true;
            }
            if (!any || consumed != text.length()) {
                return Parsed.rejected("Enter a duration, such as 30s or 1h30m.");
            }
            return Parsed.of(total);
        };

        private static final Pattern SPACES = Pattern.compile("\\s+");
        private static final Pattern NOT_ID = Pattern.compile("[^a-z0-9_-]");
        private static final Pattern EDGES = Pattern.compile("^[_-]+|[_-]+$");

        static final InputParser<String> ID = raw -> {
            // Locale.ROOT, not the host's: in a Turkish locale toLowerCase maps
            // I to a dotless i, so the same name typed on two servers becomes
            // two different ids. ExyliaCommons had this bug.
            String folded = SPACES.matcher(raw.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
            String trimmed = EDGES.matcher(folded).replaceAll("");
            if (trimmed.isEmpty()) {
                return Parsed.rejected("Enter an id.");
            }
            if (NOT_ID.matcher(trimmed).find()) {
                return Parsed.rejected("Use only letters, numbers, - and _.");
            }
            return Parsed.of(trimmed);
        };

        static final InputParser<String> SLUG = raw -> {
            String folded = SPACES.matcher(raw.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
            String cleaned = NOT_ID.matcher(folded).replaceAll("");
            String trimmed = EDGES.matcher(cleaned).replaceAll("");
            return trimmed.isEmpty()
                    ? Parsed.rejected("Enter something that can become an id.")
                    : Parsed.of(trimmed);
        };
    }
}
