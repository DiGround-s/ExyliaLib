package net.exylia.lib.util;

import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * A comparison written in a config file, parsed once and asked many times.
 *
 * <pre>{@code
 * Condition unlocked = Condition.parse("%player_level% >= 5");
 * if (unlocked.test(player, false)) { ... }   // unreadable counts as not met
 * }</pre>
 *
 * <p>The notation is the one rewards, effects and holograms already read: a left side,
 * one of {@code >= <= == != > <}, a right side, with placeholders resolved on both.
 * {@code ==} and {@code !=} compare text ignoring case; the others compare numbers.
 * A blank condition holds; {@code true} and {@code false} (also as what a lone
 * placeholder resolves to) mean themselves.
 *
 * <p>The operator is found in the text as written, so a placeholder whose value
 * contains {@code >} cannot move it.
 *
 * <h2>Unreadable is the caller's choice</h2>
 * Every test names what an unreadable condition answers: a reward that should not be
 * withheld by a typo passes {@code true}, a quest step that must not complete by one
 * passes {@code false}. {@link #problem()} says what is wrong with the text, so the
 * caller can report it once at load; a side that does not resolve to a number is only
 * known when it is tested, and answers the same fallback.
 *
 * <p>Immutable and safe to share. Resolving placeholders is only as thread safe as the
 * resolvers involved.
 *
 * @since 1.392.0
 */
public final class Condition {

    /** Longest first: {@code >=} must be tried before {@code >}. */
    private static final String[] OPERATORS = {">=", "<=", "==", "!=", ">", "<"};

    private static final Condition ALWAYS = new Condition("", null, "", "", null);

    private final String text;
    private final @Nullable String operator;
    private final String left;
    private final String right;
    private final @Nullable String problem;

    private Condition(String text, @Nullable String operator, String left, String right,
                      @Nullable String problem) {
        this.text = text;
        this.operator = operator;
        this.left = left;
        this.right = right;
        this.problem = problem;
    }

    /**
     * Parses a condition. Never throws: unreadable text gives a condition whose
     * {@link #problem()} says why and whose tests answer the caller's fallback.
     *
     * @param text the condition as written, or {@code null} for one that always holds
     * @return the condition
     */
    public static @NotNull Condition parse(@Nullable String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return ALWAYS;
        }
        for (String operator : OPERATORS) {
            int at = trimmed.indexOf(operator);
            if (at < 0) {
                continue;
            }
            String left = trimmed.substring(0, at).trim();
            String right = trimmed.substring(at + operator.length()).trim();
            String problem = left.isEmpty() || right.isEmpty()
                    ? "\"" + trimmed + "\" is missing a side of its " + operator : null;
            return new Condition(trimmed, operator, left, right, problem);
        }
        // No operator: only true/false, or a placeholder that resolves to one.
        String lower = trimmed.toLowerCase(Locale.ROOT);
        boolean literal = lower.equals("true") || lower.equals("false");
        return new Condition(trimmed, null, trimmed, "",
                literal || trimmed.indexOf('%') >= 0 ? null
                        : "\"" + trimmed + "\" is not a comparison");
    }

    /**
     * Whether it holds for a player, resolving their placeholders.
     *
     * @param player         whose placeholders to resolve, or {@code null}
     * @param whenUnreadable what to answer when the text or a resolved side cannot be read
     * @return whether it holds
     */
    public boolean test(@Nullable Player player, boolean whenUnreadable) {
        return test(side -> side.indexOf('%') < 0 ? side : Placeholders.apply(side, player),
                whenUnreadable);
    }

    /**
     * Whether it holds, with each side resolved by the caller.
     *
     * @param resolve        turns a side as written into its value
     * @param whenUnreadable what to answer when the text or a resolved side cannot be read
     * @return whether it holds
     */
    public boolean test(@NotNull UnaryOperator<String> resolve, boolean whenUnreadable) {
        if (this == ALWAYS) {
            return true;
        }
        if (problem != null) {
            return whenUnreadable;
        }
        String a = resolve.apply(left).trim();
        if (operator == null) {
            return switch (a.toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> whenUnreadable;
            };
        }
        String b = resolve.apply(right).trim();
        if (operator.equals("==")) {
            return a.equalsIgnoreCase(b);
        }
        if (operator.equals("!=")) {
            return !a.equalsIgnoreCase(b);
        }
        Double x = number(a);
        Double y = number(b);
        if (x == null || y == null) {
            return whenUnreadable;
        }
        return switch (operator) {
            case ">=" -> x >= y;
            case "<=" -> x <= y;
            case ">" -> x > y;
            default -> x < y;
        };
    }

    /** What is wrong with the text as written, or {@code null} when it reads. */
    public @Nullable String problem() {
        return problem;
    }

    /** The condition as written, trimmed. */
    public @NotNull String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    private static @Nullable Double number(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
