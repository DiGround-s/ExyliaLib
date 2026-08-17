package net.exylia.lib.economy.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conversion that decides whether a balance keeps its cents.
 *
 * <p>Vault hands out {@code double}s and PlayerPoints hands out {@code int}s.
 * Neither is the {@link BigDecimal} the library deals in, so the adapters
 * convert — and the wrong conversion is invisible until a player adds up their
 * balance by hand and it does not match. These tests pin the exact conversions
 * both adapters are documented to use, so a future edit that "simplifies" one
 * fails here rather than on a live server.
 */
class PrecisionTest {

    @Test
    @DisplayName("BigDecimal.valueOf reads the decimal, new BigDecimal reads the binary noise")
    void valueOfVsConstructor() {
        // The reason both adapters are documented to use valueOf: the double
        // nearest 0.1 is not 0.1, and the constructor keeps every bit of that
        // while valueOf reads the shortest decimal that round-trips.
        double vaultSaid = 0.1;

        BigDecimal viaValueOf = BigDecimal.valueOf(vaultSaid);
        BigDecimal viaConstructor = new BigDecimal(vaultSaid);

        assertEquals(0, new BigDecimal("0.1").compareTo(viaValueOf));
        assertNotEquals(0, new BigDecimal("0.1").compareTo(viaConstructor),
                "the constructor preserves the binary error valueOf exists to hide");
        assertEquals("0.1", viaValueOf.toPlainString());
    }

    @Test
    @DisplayName("three deposits of 0.1 sum to 0.3, not to a number a player can prove wrong")
    void depositsSumExactly() {
        // The case the whole precision rule exists for: a shop adding prices.
        BigDecimal sum = BigDecimal.valueOf(0.1)
                .add(BigDecimal.valueOf(0.1))
                .add(BigDecimal.valueOf(0.1));

        assertEquals(0, new BigDecimal("0.3").compareTo(sum), "got " + sum.toPlainString());

        double asDouble = 0.1 + 0.1 + 0.1;
        assertTrue(BigDecimal.valueOf(asDouble).compareTo(new BigDecimal("0.3")) != 0,
                "this is what the double path would have shown");
    }

    @Test
    @DisplayName("an int of points converts exactly")
    void pointsAreExact() {
        // PlayerPoints is integer; valueOf(long) cannot lose anything.
        assertEquals(0, new BigDecimal("1000000").compareTo(BigDecimal.valueOf(1_000_000L)));
    }

    @Test
    @DisplayName("a fractional point amount is not silently an int")
    void fractionalPointsAreCaught() {
        // A point currency cannot hold half a point. The honest answer is to
        // refuse, which is what setScale(0, UNNECESSARY) + intValueExact does.
        BigDecimal oneAndAHalf = new BigDecimal("1.5");

        boolean refused = false;
        try {
            oneAndAHalf.setScale(0, java.math.RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException mustBeWhole) {
            refused = true;
        }
        assertTrue(refused, "1.5 points must be refused, not truncated to 1 or rounded to 2");
    }

    @Test
    @DisplayName("a point amount beyond an int is refused, not wrapped negative")
    void pointsOverflowIsCaught() {
        // intValue() would wrap past Integer.MAX_VALUE into a negative balance.
        BigDecimal tooMany = new BigDecimal("3000000000");

        boolean refused = false;
        try {
            tooMany.setScale(0, java.math.RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException tooLarge) {
            refused = true;
        }
        assertTrue(refused, "3_000_000_000 points must be refused, not wrapped to a negative");
    }

    @Test
    @DisplayName("a whole point amount in range converts")
    void wholePointsInRange() {
        int points = new BigDecimal("64").setScale(0, java.math.RoundingMode.UNNECESSARY).intValueExact();
        assertEquals(64, points);
    }
}
