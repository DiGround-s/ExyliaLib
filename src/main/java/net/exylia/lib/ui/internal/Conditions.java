package net.exylia.lib.ui.internal;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Whether a slot is shown.
 *
 * <p>Conditions are written by server owners, so the grammar is one comparison
 * and nothing else:
 *
 * <pre>
 * condition: "%lfc_state% == none"
 * condition: "%has_teams% != true"
 * condition: "%total_events% > 0"
 * </pre>
 *
 * <p>Placeholders are resolved before this sees the text, so what arrives is
 * two literals and an operator. Across the whole ecosystem only {@code ==} and
 * {@code !=} are used; the rest are here because they cost four lines and their
 * absence would be a surprise.
 *
 * <p>Anything that is not a comparison is read as a boolean, so
 * {@code condition: "%is_owner%"} works. Anything unreadable is
 * {@code false}: a slot that should have been hidden and is shown is worse than
 * one that should have been shown and is hidden — the second is visible, the
 * first hands a button to somebody who should not have it.
 */
public final class Conditions {

    /**
     * The operators, longest first.
     *
     * <p>Order matters: {@code >=} has to be tried before {@code >}, or
     * {@code "5 >= 3"} splits into {@code "5"} and {@code "= 3"}.
     */
    private static final String[] OPERATORS =
            {"==", "!=", ">=", "<=", ">", "<", " contains ", " startsWith ", " endsWith "};

    private Conditions() {
    }

    /**
     * Evaluates a resolved condition.
     *
     * @param condition the condition, with placeholders already substituted
     * @return whether the slot is shown
     */
    public static boolean test(String condition) {
        return test(condition, UnaryOperator.identity());
    }

    /**
     * Tests a condition whose placeholders are filled in after it is split.
     *
     * <p>The operator is found in the condition as written, and each side is
     * resolved on its own. Resolving first let a value carry an operator of its
     * own: a row named {@code x == x} turned {@code %name% == admin} into a
     * comparison that always held.
     *
     * @param condition the condition as written
     * @param resolve   fills one side's placeholders in
     * @return whether it holds
     */
    public static boolean test(String condition, UnaryOperator<String> resolve) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String text = condition.trim();
        for (String operator : OPERATORS) {
            int at = text.indexOf(operator);
            if (at <= 0) {
                continue;
            }
            String left = resolve.apply(text.substring(0, at).trim()).trim();
            String right = resolve.apply(text.substring(at + operator.length()).trim()).trim();
            return compare(left, operator.trim(), right);
        }
        return Boolean.parseBoolean(resolve.apply(text).trim());
    }

    private static boolean compare(String left, String operator, String right) {
        return switch (operator) {
            case "==" -> left.equalsIgnoreCase(right);
            case "!=" -> !left.equalsIgnoreCase(right);
            case "contains" -> left.contains(right);
            case "startsWith" -> left.startsWith(right);
            case "endsWith" -> left.endsWith(right);
            default -> numeric(left, operator, right);
        };
    }

    /**
     * Compares two numbers.
     *
     * <p>A side that is not a number is {@code false} rather than an exception:
     * a placeholder that failed to resolve leaves its own name in the text, and
     * that is a condition nobody should pass.
     */
    private static boolean numeric(String left, String operator, String right) {
        try {
            double first = Double.parseDouble(left);
            double second = Double.parseDouble(right);
            return switch (operator) {
                case ">" -> first > second;
                case "<" -> first < second;
                case ">=" -> first >= second;
                case "<=" -> first <= second;
                default -> false;
            };
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** Returns whether a condition names anything that has to be resolved. */
    public static boolean isDynamic(String condition) {
        return condition != null && condition.indexOf('%') >= 0;
    }

    /** Lowercases an operator name the way configuration writes it. */
    static String normalise(String operator) {
        return operator.trim().toLowerCase(Locale.ROOT);
    }
}
