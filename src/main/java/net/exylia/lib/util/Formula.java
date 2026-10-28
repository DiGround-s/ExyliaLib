package net.exylia.lib.util;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A formula read once by {@link Expressions#compile}, worked out as often as
 * needed without parsing again.
 *
 * <pre>{@code
 * Formula damage = Expressions.compile("0.5 * level + bonus", "level", "bonus");
 * double hit = damage.applyOr(1.0, 3, 0.5);   // 2.0
 * }</pre>
 *
 * <h2>When the arithmetic has no answer</h2>
 * A text that reads fine can still divide by zero for some values. Then
 * {@link #apply} answers {@link Double#NaN} and {@link #applyOr} the fallback,
 * the same rule {@link Expressions#evaluate} follows: an infinity is a broken
 * formula, not a number.
 *
 * <p>Immutable and safe to share between threads.
 *
 * @since 1.385.0
 */
public final class Formula {

    private final String source;
    private final List<String> variables;
    private final Expressions.Node root;
    private final boolean constant;
    private final double value;

    Formula(String source, String[] variables, Expressions.Node root) {
        this.source = source;
        this.variables = List.of(variables);
        this.root = root;
        // A formula naming none of its variables is worked out once, here.
        double folded = Double.NaN;
        boolean foldable = !mentionsAny(source, variables);
        if (foldable) {
            folded = evaluate(root, new double[variables.length]);
        }
        this.constant = foldable;
        this.value = folded;
    }

    /**
     * Works the formula out.
     *
     * @param values the variables' values, in the order they were declared;
     *               a missing one counts as {@code 0}
     * @return the result, or {@link Double#NaN} when the arithmetic has no answer
     */
    public double apply(double... values) {
        return constant ? value : evaluate(root, values);
    }

    /**
     * Works the formula out, with a fallback for arithmetic that has no answer.
     *
     * @param fallback what to answer instead of {@code NaN}
     * @param values   the variables' values, in the order they were declared
     * @return the result, or {@code fallback}
     */
    public double applyOr(double fallback, double... values) {
        double result = apply(values);
        return Double.isNaN(result) ? fallback : result;
    }

    /** Whether the result is the same whatever the variables are. */
    public boolean isConstant() {
        return constant;
    }

    /** The formula as written. */
    public @NotNull String source() {
        return source;
    }

    /** The variable names it was compiled with, in order. */
    public @NotNull List<String> variables() {
        return variables;
    }

    @Override
    public String toString() {
        return source;
    }

    private static double evaluate(Expressions.Node root, double[] values) {
        try {
            double result = root.eval(values);
            return Double.isFinite(result) ? result : Double.NaN;
        } catch (ArithmeticException | StackOverflowError noAnswer) {
            return Double.NaN;
        }
    }

    private static boolean mentionsAny(String source, String[] variables) {
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        for (String variable : variables) {
            if (lower.contains(variable.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
