package net.exylia.lib.util.reward.internal;

import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Whether a reward's condition holds.
 *
 * <p>The notation is ExyliaCommons': a left side, an operator and a right side,
 * with placeholders resolved on both. {@code %player_level% >= 10}. It is a toy
 * expression language and stays one &mdash; a server owner who needs more than a
 * comparison has a permission, and a plugin that needs more than that should not
 * be expressing it in a string.
 *
 * <h2>An unreadable condition is reported, not swallowed</h2>
 * Commons returned {@code false} for anything it could not parse, so a typo in a
 * condition deleted the reward and nobody found out until a player complained.
 * Here it is reported once and the reward is <em>given</em>: the config said who
 * should be excluded, and a condition nobody can read excludes nobody.
 *
 * <p>Failing open is the deliberate opposite of the menu module, where an
 * unreadable condition hides a slot. Hiding a button is invisible; handing out a
 * reward that should have been withheld is loud, and loud is what gets a typo
 * fixed.
 */
public final class Conditions {

    private Conditions() {
        throw new AssertionError("No instances.");
    }

    /** Longest first: {@code >=} must be tried before {@code >}. */
    private static final String[] OPERATORS = {">=", "<=", "==", "!=", ">", "<"};

    /**
     * Evaluates a condition.
     *
     * <p>A problem is reported as two strings: what makes it the same problem
     * across players, and what to print. They differ because the printed message
     * carries the values this player's placeholders resolved to &mdash; useful to
     * read, ruinous as a key, since keyed on that a broken condition reports once
     * per player forever.
     *
     * @param condition the condition as written
     * @param player    whose placeholders to resolve, or {@code null}
     * @param problems  told the subject and the message, when it cannot be read
     * @return whether the reward should be given
     */
    public static boolean holds(@NotNull String condition,
                                @Nullable Player player,
                                @NotNull BiConsumer<String, String> problems) {
        String resolved = resolve(condition, player).trim();
        if (resolved.isEmpty()) {
            return true;
        }
        String lower = resolved.toLowerCase(Locale.ROOT);
        if (lower.equals("true")) {
            return true;
        }
        if (lower.equals("false")) {
            return false;
        }
        for (String operator : OPERATORS) {
            int at = resolved.indexOf(operator);
            if (at < 0) {
                continue;
            }
            String left = resolved.substring(0, at).trim();
            String right = resolved.substring(at + operator.length()).trim();
            if (left.isEmpty() || right.isEmpty()) {
                problems.accept(condition, "\"" + condition + "\" is missing a side of its "
                        + operator + "; the reward is given anyway");
                return true;
            }
            return compare(condition, operator, left, right, problems);
        }
        problems.accept(condition, "\"" + condition
                + "\" is not a comparison; the reward is given anyway");
        return true;
    }

    private static boolean compare(String original, String operator,
                                   String left, String right,
                                   BiConsumer<String, String> problems) {
        if (operator.equals("==")) {
            return left.equalsIgnoreCase(right);
        }
        if (operator.equals("!=")) {
            return !left.equalsIgnoreCase(right);
        }
        Double a = number(left);
        Double b = number(right);
        if (a == null || b == null) {
            // The subject is the condition as written; the message names the
            // value this player's placeholders produced, which differs per
            // player and must not reach the key.
            problems.accept(original, "\"" + original + "\" compares something that is not"
                    + " a number (" + (a == null ? left : right) + "); the reward is given anyway");
            return true;
        }
        return switch (operator) {
            case ">=" -> a >= b;
            case "<=" -> a <= b;
            case ">" -> a > b;
            case "<" -> a < b;
            default -> true;
        };
    }

    private static @Nullable Double number(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String resolve(String condition, @Nullable Player player) {
        if (condition.indexOf('%') < 0) {
            return condition;
        }
        return Placeholders.apply(condition, player);
    }
}
