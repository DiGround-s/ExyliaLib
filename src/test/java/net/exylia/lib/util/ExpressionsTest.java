package net.exylia.lib.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a formula in a config file is allowed to say, and what happens when it
 * says something else.
 *
 * <p>No server is involved: the parser is pure, which is the point of keeping
 * the placeholder pass outside it.
 */
class ExpressionsTest {

    private static double eval(String formula) {
        OptionalDouble result = Expressions.tryEvaluate(formula);
        assertTrue(result.isPresent(), "should be readable: " + formula);
        return result.getAsDouble();
    }

    private static void unreadable(String formula) {
        assertTrue(Expressions.tryEvaluate(formula).isEmpty(), "should be rejected: " + formula);
    }

    @Test
    @DisplayName("applies the precedence a config author writes on paper")
    void precedence() {
        assertEquals(7.0, eval("1 + 2 * 3"));
        assertEquals(9.0, eval("(1 + 2) * 3"));
        assertEquals(2.5, eval("10 / 4"));
        assertEquals(1.0, eval("7 % 3"));
        assertEquals(-6.0, eval("-2 * 3"));
        assertEquals(5.0, eval("  5  "));
    }

    @Test
    @DisplayName("raises to a power, right to left")
    void powers() {
        assertEquals(8.0, eval("2 ^ 3"));
        assertEquals(512.0, eval("2 ^ 3 ^ 2"), "^ binds right, so this is 2^(3^2)");
        assertEquals(10.0, eval("2 ^ 3 + 2"), "^ binds tighter than +");
        assertEquals(16.0, eval("2 * 2 ^ 3"), "and tighter than *");
    }

    @Test
    @DisplayName("clamps and rounds through the functions a damage formula needs")
    void functions() {
        assertEquals(4.0, eval("min(4, 9)"));
        assertEquals(9.0, eval("max(4, 9)"));
        assertEquals(3.0, eval("abs(0 - 3)"));
        assertEquals(2.0, eval("floor(2.9)"));
        assertEquals(3.0, eval("ceil(2.1)"));
        assertEquals(3.0, eval("round(2.5)"));
        assertEquals(4.0, eval("sqrt(16)"));
        assertEquals(20.0, eval("min(20, 4 * 8)"), "arguments are whole expressions");
        assertEquals(6.0, eval("max(2, min(6, 8))"), "and functions nest");
    }

    @Test
    @DisplayName("a formula that cannot be read gives the caller their fallback")
    void fallback() {
        assertEquals(4.0, Expressions.evaluate("banana", 4.0));
        assertEquals(4.0, Expressions.evaluate("", 4.0));
        assertEquals(4.0, Expressions.evaluate("2 +", 4.0));
        assertEquals(6.0, Expressions.evaluate("2 * 3", 4.0), "and a good one does not");
    }

    @Test
    @DisplayName("rejects what it cannot mean, rather than guessing")
    void rejections() {
        unreadable("2 +");
        unreadable("(2 + 3");
        unreadable("2 3");
        unreadable("%unresolved_placeholder% + 1");
        unreadable("2 / 0");
        unreadable("2 % 0");
        unreadable("sqrt(0 - 4)");
        unreadable("nonsense(2)");
        unreadable("min(2)");
        unreadable("abs(1, 2)");
    }

    @Test
    @DisplayName("an infinite result is a broken formula, not a number")
    void infinityIsNotAnAnswer() {
        // Reachable without dividing by zero, and just as ruinous downstream.
        unreadable("9 ^ 9 ^ 9");
        assertEquals(1.0, Expressions.evaluate("9 ^ 9 ^ 9", 1.0));
    }

    @Test
    @DisplayName("deep nesting is a typo, not a crash")
    void deepNestingDoesNotCrash() {
        unreadable("(".repeat(50_000) + "1");
    }
}
