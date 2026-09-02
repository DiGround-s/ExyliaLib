package net.exylia.lib.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player is allowed to type into {@code /pay <player> <amount>}.
 *
 * <p>The interesting half of this is not the suffixes, it is what gets refused.
 * A transfer command that reads {@code "1,5"} as fifteen is not a formatting
 * bug, it is a bug report about somebody's money.
 */
class AmountsTest {

    private static void reads(String input, String expected) {
        Optional<BigDecimal> parsed = Amounts.parse(input);
        assertTrue(parsed.isPresent(), () -> "should have read " + input);
        assertEquals(0, new BigDecimal(expected).compareTo(parsed.get()),
                () -> input + " -> " + parsed.get() + ", expected " + expected);
    }

    private static void refuses(String input) {
        assertFalse(Amounts.parse(input).isPresent(),
                () -> input + " should have been refused, got " + Amounts.parse(input));
    }

    @Test
    @DisplayName("the suffixes a player actually types")
    void suffixes() {
        reads("10M", "10000000");
        reads("10m", "10000000");
        reads("1.5k", "1500");
        reads("1.5K", "1500");
        reads("2B", "2000000000");
        reads("3T", "3000000000000");
        reads("1Q", "1000000000000000");
    }

    @Test
    @DisplayName("a plain number, with or without decimals")
    void plainNumbers() {
        reads("100", "100");
        reads("0", "0");
        reads("12.34", "12.34");
        reads("  42  ", "42");
    }

    @Test
    @DisplayName("separators that mean the same thing in every convention")
    void unambiguousSeparators() {
        // Three digits after a comma is a thousands separator whether the
        // player learned to write numbers in London or in Madrid.
        reads("2,500", "2500");
        reads("1,234,567", "1234567");
        // An underscore is never a decimal point anywhere.
        reads("2_500", "2500");
        reads("1_000_000", "1000000");
    }

    @Test
    @DisplayName("\"1,5\" is refused rather than guessed")
    void ambiguousCommaIsRefused() {
        // Fifteen tenths in most of Europe, fifteen with a stray separator
        // elsewhere. Guessing either way is a transfer of the wrong amount, and
        // the player who typed it cannot tell which they got until afterwards.
        refuses("1,5");
        refuses("1,50");
        refuses("0,5");
    }

    @Test
    @DisplayName("a comma and a dot together is refused")
    void mixedSeparatorsAreRefused() {
        // "1.234,56" and "1,234.56" are the same number written by two people
        // who would each read the other's as wrong.
        refuses("1,234.56");
        refuses("1.234,56");
    }

    @Test
    @DisplayName("nonsense is refused, never turned into zero")
    void nonsense() {
        // Returning zero would make "/pay bob abc" a silent no-op that looks
        // like it worked.
        refuses("abc");
        refuses("");
        refuses("   ");
        refuses(null);
        refuses("M");
        refuses("10X");
        refuses("1.2.3");
        refuses("--5");
    }

    @Test
    @DisplayName("a negative amount is refused")
    void negatives() {
        // Every command this exists for — pay, deposit, give — is a transfer.
        // A negative one is a withdrawal wearing a disguise.
        refuses("-10");
        refuses("-1M");

        // A setting is not a transfer: -1 is how a field says unlimited.
        assertEquals(0, new BigDecimal("-10").compareTo(Amounts.parseSigned("-10").orElseThrow()));
        assertEquals(-1L, Amounts.parseSignedWhole("-1").orElseThrow());
        assertFalse(Amounts.parseSigned("--5").isPresent());
    }

    @Test
    @DisplayName("money keeps every decimal it was typed with")
    void precision() {
        // The reason this returns BigDecimal. Parsing "0.1" into a double and
        // adding it three times does not give 0.3, and a player counting coins
        // notices before the developer does.
        BigDecimal sum = Amounts.parse("0.1").orElseThrow()
                .add(Amounts.parse("0.1").orElseThrow())
                .add(Amounts.parse("0.1").orElseThrow());

        assertEquals(0, new BigDecimal("0.3").compareTo(sum), "got " + sum);
    }

    @Test
    @DisplayName("a suffix keeps precision too")
    void suffixPrecision() {
        reads("1.005M", "1005000");
        reads("0.001k", "1");
    }

    @Test
    @DisplayName("an amount far past a long still reads")
    void veryLargeAmounts() {
        // A server with an inflated economy really does have balances past
        // Long.MAX_VALUE, and a parser that overflows there gives somebody a
        // negative balance.
        reads("999Q", "999000000000000000");
        assertTrue(Amounts.parse("999Q").orElseThrow()
                .compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0);
        reads("9999999Q", "9999999000000000000000");
    }

    @Test
    @DisplayName("parsing does not depend on the server's locale")
    void localeIndependent() {
        // The host's locale decides how Java writes numbers, and a naive parser
        // inherits that. The same command typed on two servers must do the same
        // thing.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"));
            reads("1.5k", "1500");
            reads("12.34", "12.34");
            refuses("1,5");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("a whole amount refuses a fraction rather than truncating it")
    void wholeAmounts() {
        // For an item count or a level. Somebody typing 1.5 meant something,
        // and silently giving them one is not it.
        assertEquals(64L, Amounts.parseWhole("64").orElseThrow());
        assertEquals(10_000_000L, Amounts.parseWhole("10M").orElseThrow());
        assertFalse(Amounts.parseWhole("1.5").isPresent());
        assertEquals(999_000_000_000_000_000L, Amounts.parseWhole("999Q").orElseThrow());
        assertFalse(Amounts.parseWhole("9999999Q").isPresent(),
                "past a long, so it cannot be given exactly");
    }

    @Test
    @DisplayName("a fallback covers what cannot be read")
    void fallback() {
        assertEquals(0, BigDecimal.TEN.compareTo(Amounts.parseOr("nope", BigDecimal.TEN)));
        assertEquals(0, new BigDecimal("5").compareTo(
                Amounts.parseOr("5", BigDecimal.TEN)));
    }

    @Test
    @DisplayName("what a player types comes back the way it is displayed")
    void roundTripWithDisplay() {
        // The pair that matters: a player reads "10M" on a scoreboard, types it
        // into /pay, and gets the number they saw.
        assertEquals("10M", Numbers.compact(Amounts.parse("10M").orElseThrow().longValue()));
        assertEquals("1.5K", Numbers.compact(Amounts.parse("1.5k").orElseThrow().longValue()));
        assertEquals("2.5M", Numbers.compact(Amounts.parse("2.5M").orElseThrow().longValue()));
    }
}
