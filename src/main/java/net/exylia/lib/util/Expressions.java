package net.exylia.lib.util;

import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Arithmetic written in a config file.
 *
 * <pre>{@code
 * double damage = Expressions.evaluate("2 + %distance% * 0.5", player, 4.0);
 * double clamped = Expressions.evaluate("min(20, %hearts% * 2)", player, 20.0);
 * }</pre>
 *
 * <p>The companion of {@link net.exylia.lib.util.internal.Conditions}, which
 * answers whether something holds. This one answers how much. A config that can
 * say {@code damage: 2 + %kills% * 0.5} is the difference between a server owner
 * tuning a number and asking for a plugin update.
 *
 * <h2>What it understands</h2>
 * {@code + - * / % ^}, parentheses, unary sign, and the functions {@code min},
 * {@code max}, {@code abs}, {@code floor}, {@code ceil}, {@code round} and
 * {@code sqrt}. Nothing else, deliberately: this is a formula in a YAML file,
 * not a scripting language. Placeholders are filled in before parsing, so
 * everything the caller can name is available as a number.
 *
 * <h2>An unreadable formula falls back, loudly enough</h2>
 * Every entry point takes a fallback and returns it when the text cannot be
 * read, so a typo costs the owner their tuning and not the feature. Callers who
 * need to tell "the formula said zero" from "the formula was broken" use
 * {@link #tryEvaluate}, which answers with an empty {@code OptionalDouble}
 * instead.
 *
 * <p>A division by zero is a broken formula, not an infinity: returning
 * {@code Infinity} propagates into damage and cooldown numbers where it does
 * far more damage than the fallback would.
 *
 * <h2>Threading</h2>
 * Parsing touches no Bukkit state and is safe anywhere. Resolving placeholders
 * is only as thread safe as the resolvers involved, which for PlaceholderAPI
 * means the main thread; pass a {@code null} player from elsewhere.
 *
 * @since 1.63.0
 */
public final class Expressions {

    private Expressions() {
        throw new AssertionError("No instances.");
    }

    /**
     * Evaluates a formula with no placeholders in it.
     *
     * @param formula  the formula as written
     * @param fallback what to return when it cannot be read
     * @return the result, or {@code fallback}
     */
    public static double evaluate(@NotNull String formula, double fallback) {
        return tryEvaluate(formula).orElse(fallback);
    }

    /**
     * Evaluates a formula, resolving a player's placeholders first.
     *
     * @param formula  the formula as written
     * @param player   whose placeholders to resolve, or {@code null}
     * @param fallback what to return when it cannot be read
     * @return the result, or {@code fallback}
     */
    public static double evaluate(@NotNull String formula, @Nullable Player player, double fallback) {
        return tryEvaluate(Placeholders.apply(formula, player)).orElse(fallback);
    }

    /**
     * Evaluates a formula with extra values attached to the placeholder pass.
     *
     * @param formula  the formula as written
     * @param player   whose placeholders to resolve, or {@code null}
     * @param data     values resolvers can read, as {@link Placeholders#apply}
     *                 takes them
     * @param fallback what to return when it cannot be read
     * @return the result, or {@code fallback}
     */
    public static double evaluate(@NotNull String formula, @Nullable Player player,
                                  @NotNull Map<String, Object> data, double fallback) {
        return tryEvaluate(Placeholders.apply(formula, player, data)).orElse(fallback);
    }

    /**
     * Evaluates a formula that has already had its placeholders filled in.
     *
     * <p>The form to use when the difference between a result of zero and an
     * unreadable formula matters &mdash; reporting the typo, for instance.
     *
     * @param formula the formula, with no placeholders left in it
     * @return the result, or empty when it cannot be read
     */
    public static @NotNull OptionalDouble tryEvaluate(@NotNull String formula) {
        try {
            Parser parser = new Parser(formula);
            double value = parser.expression();
            parser.skipSpace();
            if (!parser.done()) {
                return OptionalDouble.empty();
            }
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (ArithmeticException | IllegalArgumentException | StackOverflowError unreadable) {
            // StackOverflowError is in here because deep nesting is reachable
            // from a config file: "((((((..." is a typo, not a crash.
            return OptionalDouble.empty();
        }
    }

    /**
     * A recursive-descent parser over the formula text.
     *
     * <p>Precedence, loosest first: {@code + -}, then {@code * / %}, then
     * {@code ^}, then a signed atom. {@code ^} binds right, so
     * {@code 2 ^ 3 ^ 2} is 512 the way it is on paper.
     */
    private static final class Parser {

        private final String source;
        private int at;

        private Parser(String source) {
            this.source = source;
        }

        private double expression() {
            double value = term();
            while (true) {
                skipSpace();
                if (take('+')) {
                    value += term();
                } else if (take('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        private double term() {
            double value = power();
            while (true) {
                skipSpace();
                if (take('*')) {
                    value *= power();
                } else if (take('/')) {
                    value = divide(value, power());
                } else if (take('%')) {
                    double divisor = power();
                    if (divisor == 0.0) {
                        throw new ArithmeticException("Remainder by zero");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        private double power() {
            double base = atom();
            skipSpace();
            if (take('^')) {
                // Right associative: the exponent is a whole power chain.
                return Math.pow(base, power());
            }
            return base;
        }

        private double atom() {
            skipSpace();
            if (take('-')) {
                return -atom();
            }
            if (take('+')) {
                return atom();
            }
            if (take('(')) {
                double value = expression();
                skipSpace();
                if (!take(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                return value;
            }
            if (!done() && Character.isLetter(source.charAt(at))) {
                return function();
            }
            return number();
        }

        private double function() {
            int start = at;
            while (!done() && Character.isLetter(source.charAt(at))) {
                at++;
            }
            String name = source.substring(start, at).toLowerCase(Locale.ROOT);

            skipSpace();
            if (!take('(')) {
                throw new IllegalArgumentException("Expected ( after " + name);
            }
            double first = expression();
            skipSpace();

            double result;
            if (take(',')) {
                double second = expression();
                skipSpace();
                result = switch (name) {
                    case "min" -> Math.min(first, second);
                    case "max" -> Math.max(first, second);
                    default -> throw new IllegalArgumentException(name + " takes one value, not two");
                };
            } else {
                result = switch (name) {
                    case "abs" -> Math.abs(first);
                    case "floor" -> Math.floor(first);
                    case "ceil" -> Math.ceil(first);
                    case "round" -> Math.round(first);
                    case "sqrt" -> sqrt(first);
                    case "min", "max" -> throw new IllegalArgumentException(name + " takes two values");
                    default -> throw new IllegalArgumentException("Unknown function " + name);
                };
            }

            if (!take(')')) {
                throw new IllegalArgumentException("Missing closing parenthesis after " + name);
            }
            return result;
        }

        private double number() {
            skipSpace();
            int start = at;
            while (!done() && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) {
                at++;
            }
            if (start == at) {
                throw new IllegalArgumentException("Expected a number at position " + at);
            }
            return Double.parseDouble(source.substring(start, at));
        }

        private static double divide(double value, double divisor) {
            if (divisor == 0.0) {
                throw new ArithmeticException("Division by zero");
            }
            return value / divisor;
        }

        private static double sqrt(double value) {
            if (value < 0.0) {
                throw new ArithmeticException("Square root of a negative number");
            }
            return Math.sqrt(value);
        }

        private boolean take(char expected) {
            if (done() || source.charAt(at) != expected) {
                return false;
            }
            at++;
            return true;
        }

        private void skipSpace() {
            while (!done() && Character.isWhitespace(source.charAt(at))) {
                at++;
            }
        }

        private boolean done() {
            return at >= source.length();
        }
    }
}
