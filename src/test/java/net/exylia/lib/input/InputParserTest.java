package net.exylia.lib.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one place text becomes a value.
 *
 * <p>ExyliaCommons parsed separately inside its chat handler, its dialog
 * handler and its Floodgate handler, so the same answer could be accepted in a
 * dialog and rejected in chat. These tests pin the behaviour that every
 * transport now shares, so a change here is a change everywhere rather than in
 * one of four copies.
 */
class InputParserTest {

    private static <T> T parsed(InputParser<T> parser, String raw) {
        InputParser.Parsed<T> result = parser.parse(raw);
        assertTrue(result.ok(), () -> raw + " should have parsed, got: " + result.error());
        return result.value();
    }

    private static void rejects(InputParser<?> parser, String raw) {
        InputParser.Parsed<?> result = parser.parse(raw);
        assertFalse(result.ok(), () -> raw + " should have been rejected, got " + result.value());
        assertFalse(result.error().isBlank(), "a rejection must say why");
    }

    @Test
    @DisplayName("a number field understands what a player types into a pay command")
    void integersAcceptSuffixes() {
        // The same reader the economy uses, so 10k means ten thousand in a form
        // exactly as it does in /pay. Two readers would mean two answers.
        assertEquals(10_000_000L, parsed(InputParser.integer(), "10M"));
        assertEquals(1_500L, parsed(InputParser.integer(), "1.5k"));
        assertEquals(2_500L, parsed(InputParser.integer(), "2,500"));
        assertEquals(64L, parsed(InputParser.integer(), "64"));
    }

    @Test
    @DisplayName("a fractional answer to a whole-number field is refused, not truncated")
    void integersRefuseFractions() {
        // Somebody typing 1.5 for a slot count meant something, and silently
        // giving them one is not it.
        rejects(InputParser.integer(), "1.5");
        assertEquals("Enter a whole number.", InputParser.integer().parse("1.5").error());
        assertEquals("Enter a number.", InputParser.integer().parse("abc").error());
    }

    @Test
    @DisplayName("a negative is a value, because -1 is how a field says unlimited")
    void numbersAcceptNegatives() {
        assertEquals(-1L, parsed(InputParser.integer(), "-1"));
        assertEquals(0, new BigDecimal("-2.5").compareTo(parsed(InputParser.decimal(), "-2.5")));
    }

    @Test
    @DisplayName("a decimal keeps the cents a double would have lost")
    void decimalsAreExact() {
        BigDecimal sum = parsed(InputParser.decimal(), "0.1")
                .add(parsed(InputParser.decimal(), "0.2"));
        assertEquals(0, new BigDecimal("0.3").compareTo(sum), "got " + sum);
    }

    @Test
    @DisplayName("an amount refuses what is genuinely ambiguous")
    void amountsRefuseAmbiguity() {
        assertEquals(0, new BigDecimal("10000000").compareTo(parsed(InputParser.amount(), "10M")));
        // 1,5 is one and a half in Europe and fifteen elsewhere. A form that
        // guesses is a form that takes the wrong amount of somebody's money.
        rejects(InputParser.amount(), "1,5");
        rejects(InputParser.amount(), "-5");
    }

    @Test
    @DisplayName("a yes-or-no field is generous, because being strict achieves nothing")
    void flags() {
        for (String yes : new String[] {"true", "yes", "y", "on", "1", "Enabled", "si"}) {
            assertTrue(parsed(InputParser.flag(), yes), yes);
        }
        for (String no : new String[] {"false", "no", "n", "off", "0", "Disabled"}) {
            assertFalse(parsed(InputParser.flag(), no), no);
        }
        rejects(InputParser.flag(), "maybe");
    }

    @Test
    @DisplayName("a duration reads the way a player writes one")
    void durations() {
        assertEquals(Duration.ofSeconds(30), parsed(InputParser.duration(), "30s"));
        assertEquals(Duration.ofMinutes(5), parsed(InputParser.duration(), "5m"));
        assertEquals(Duration.ofMinutes(90), parsed(InputParser.duration(), "1h30m"));
        assertEquals(Duration.ofDays(2), parsed(InputParser.duration(), "2d"));
        // A bare number is seconds: it is what somebody typing 30 into a
        // cooldown box means, and refusing it would be pedantry.
        assertEquals(Duration.ofSeconds(30), parsed(InputParser.duration(), "30"));
    }

    @Test
    @DisplayName("half a duration is not read as the whole of it")
    void durationsRefusePartialReads() {
        // "1h potato" must not quietly become one hour. A parser that reads as
        // far as it understands and stops is how a ban of 1h30x lasts an hour.
        rejects(InputParser.duration(), "1h potato");
        rejects(InputParser.duration(), "abc");
        rejects(InputParser.duration(), "");
        rejects(InputParser.duration(), "5x");
    }

    @Test
    @DisplayName("an id folds what is a typing convention and refuses what is a mistake")
    void ids() {
        assertEquals("my_arena", parsed(InputParser.id(), "My Arena"));
        assertEquals("boxing", parsed(InputParser.id(), "  BOXING  "));
        assertEquals("kit-1", parsed(InputParser.id(), "kit-1"));
        // A stray ! is a mistake, and silently dropping it is how somebody
        // creates a second arena they cannot tell from the first.
        rejects(InputParser.id(), "arena!");
        rejects(InputParser.id(), "");
        rejects(InputParser.id(), "   ");
    }

    @Test
    @DisplayName("a slug drops what an id refuses, because that is what it is for")
    void slugs() {
        assertEquals("arena", parsed(InputParser.slug(), "arena!"));
        assertEquals("my_cool_kit", parsed(InputParser.slug(), "My Cool Kit!!!"));
        rejects(InputParser.slug(), "!!!");
    }

    @Test
    @DisplayName("an id is the same on every host, whatever its locale")
    void idsAreLocaleIndependent() {
        // In a Turkish locale, toLowerCase maps I to a dotless i, so "ID" and
        // "Id" become different ids on different servers from the same input.
        // ExyliaCommons called toLowerCase() with no locale and had this bug.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("kit_i", parsed(InputParser.id(), "KIT I"));
            assertEquals("island", parsed(InputParser.id(), "ISLAND"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("a rejection always explains itself")
    void rejectionsCarryAMessage() {
        // The message is what the player reads. A blank one is a form that says
        // "no" and nothing else.
        rejects(InputParser.integer(), "x");
        rejects(InputParser.decimal(), "x");
        rejects(InputParser.amount(), "x");
        rejects(InputParser.duration(), "x");
        rejects(InputParser.flag(), "x");
        rejects(InputParser.id(), "!");
    }
}
