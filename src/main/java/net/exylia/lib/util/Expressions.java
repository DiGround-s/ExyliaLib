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
            double value = new Parser(formula, new String[0]).parse().eval(new double[0]);
            return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (ArithmeticException | IllegalArgumentException | StackOverflowError unreadable) {
            // StackOverflowError is in here because deep nesting is reachable
            // from a config file: "((((((..." is a typo, not a crash.
            return OptionalDouble.empty();
        }
    }

    /**
     * Reads a formula once, to be worked out many times.
     *
     * <pre>{@code
     * Formula chance = Expressions.compile("6 + level * 4", "level");
     * double odds = chance.apply(3);   // 18
     * }</pre>
     *
     * <p>For a number worked out on a hot path &mdash; an ability's chance on
     * every hit &mdash; where parsing the text each time is the cost. The
     * variables are real variables, not text substituted before parsing: a
     * variable called {@code level} never turns {@code levels} into
     * {@code 3s}, and {@link Formula#apply} parses nothing.
     *
     * <p>Variable names are letters, digits and {@code _}, starting with a
     * letter, matched ignoring case. A name followed by {@code (} is always a
     * function, so a variable may be called {@code max}. Placeholders are not
     * resolved here: fill them in first, or pass the value as a variable.
     *
     * @param formula   the formula as written
     * @param variables the names it may use, in the order {@link Formula#apply}
     *                  takes their values
     * @return the compiled formula
     * @throws IllegalArgumentException when the text cannot be read, with the reason
     * @since 1.385.0
     */
    public static @NotNull Formula compile(@NotNull String formula, @NotNull String... variables) {
        String[] names = variables.clone();
        try {
            return new Formula(formula, names, new Parser(formula, names).parse());
        } catch (StackOverflowError tooDeep) {
            throw new IllegalArgumentException("Nested too deeply");
        }
    }

    /** One step of a compiled formula; throws {@link ArithmeticException} when it has no answer. */
    interface Node {
        double eval(double[] values);
    }

    /**
     * A recursive-descent parser over the formula text, building the tree a
     * {@link Formula} walks.
     *
     * <p>Precedence, loosest first: {@code + -}, then {@code * / %}, then
     * {@code ^}, then a signed atom. {@code ^} binds right, so
     * {@code 2 ^ 3 ^ 2} is 512 the way it is on paper.
     */
    private static final class Parser {

        private final String source;
        private final String[] variables;
        private int at;

        private Parser(String source, String[] variables) {
            this.source = source;
            this.variables = variables;
        }

        private Node parse() {
            Node node = expression();
            skipSpace();
            if (!done()) {
                throw new IllegalArgumentException("Unexpected text at position " + at);
            }
            return node;
        }

        private Node expression() {
            Node value = term();
            while (true) {
                skipSpace();
                Node left = value;
                if (take('+')) {
                    Node right = term();
                    value = v -> left.eval(v) + right.eval(v);
                } else if (take('-')) {
                    Node right = term();
                    value = v -> left.eval(v) - right.eval(v);
                } else {
                    return value;
                }
            }
        }

        private Node term() {
            Node value = power();
            while (true) {
                skipSpace();
                Node left = value;
                if (take('*')) {
                    Node right = power();
                    value = v -> left.eval(v) * right.eval(v);
                } else if (take('/')) {
                    Node right = power();
                    value = v -> divide(left.eval(v), right.eval(v));
                } else if (take('%')) {
                    Node right = power();
                    value = v -> remainder(left.eval(v), right.eval(v));
                } else {
                    return value;
                }
            }
        }

        private Node power() {
            Node base = atom();
            skipSpace();
            if (take('^')) {
                // Right associative: the exponent is a whole power chain.
                Node exponent = power();
                return v -> Math.pow(base.eval(v), exponent.eval(v));
            }
            return base;
        }

        private Node atom() {
            skipSpace();
            if (take('-')) {
                Node inner = atom();
                return v -> -inner.eval(v);
            }
            if (take('+')) {
                return atom();
            }
            if (take('(')) {
                Node value = expression();
                skipSpace();
                if (!take(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                return value;
            }
            if (!done() && Character.isLetter(source.charAt(at))) {
                return name();
            }
            double number = number();
            return v -> number;
        }

        /** A function when a parenthesis follows, otherwise a variable. */
        private Node name() {
            int start = at;
            while (!done() && (Character.isLetterOrDigit(source.charAt(at)) || source.charAt(at) == '_')) {
                at++;
            }
            String name = source.substring(start, at).toLowerCase(Locale.ROOT);
            int afterName = at;
            skipSpace();
            if (!take('(')) {
                for (int i = 0; i < variables.length; i++) {
                    if (variables[i].equalsIgnoreCase(name)) {
                        int index = i;
                        at = afterName;
                        return v -> index < v.length ? v[index] : 0.0;
                    }
                }
                throw new IllegalArgumentException("Expected ( after " + name);
            }
            Node first = expression();
            skipSpace();

            Node result;
            if (take(',')) {
                Node second = expression();
                skipSpace();
                result = switch (name) {
                    case "min" -> v -> Math.min(first.eval(v), second.eval(v));
                    case "max" -> v -> Math.max(first.eval(v), second.eval(v));
                    default -> throw new IllegalArgumentException(name + " takes one value, not two");
                };
            } else {
                result = switch (name) {
                    case "abs" -> v -> Math.abs(first.eval(v));
                    case "floor" -> v -> Math.floor(first.eval(v));
                    case "ceil" -> v -> Math.ceil(first.eval(v));
                    case "round" -> v -> Math.round(first.eval(v));
                    case "sqrt" -> v -> sqrt(first.eval(v));
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

        private static double remainder(double value, double divisor) {
            if (divisor == 0.0) {
                throw new ArithmeticException("Remainder by zero");
            }
            return value % divisor;
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
