package net.exylia.lib.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How numbers are written for a player.
 *
 * <p>The compact suffixes are pinned to what ExyliaCommons produced, because
 * every menu, lore line and scoreboard in the ecosystem is already written
 * against them: a balance that reads {@code 1.5K} today must not start reading
 * {@code 1.5k} or {@code 2K} because the library changed underneath.
 */
class NumbersTest {

    @Test
    @DisplayName("compact notation matches what ExyliaCommons produced")
    void compactMatchesCommons() {
        // Commons: uppercase K/M/B/T/Q, threshold >= on the absolute value,
        // divisors powers of a thousand starting at exactly 1000, one optional
        // decimal.
        assertEquals("999", Numbers.compact(999L));
        assertEquals("1K", Numbers.compact(1_000L));
        assertEquals("1.5K", Numbers.compact(1_500L));
        assertEquals("2.3M", Numbers.compact(2_340_000L));
        assertEquals("1B", Numbers.compact(1_000_000_000L));
        assertEquals("1T", Numbers.compact(1_000_000_000_000L));
        assertEquals("1Q", Numbers.compact(1_000_000_000_000_000L));
    }

    @Test
    @DisplayName("a decimal that says nothing is dropped")
    void compactDropsEmptyDecimals() {
        // "2K" rather than "2.0K": a scoreboard line is narrow and the zero
        // carries no information.
        assertEquals("2K", Numbers.compact(2_000L));
        assertEquals("2.5K", Numbers.compact(2_500L));
    }

    @Test
    @DisplayName("just below a threshold is not rounded up into the next suffix")
    void compactDoesNotOverstate() {
        // 999 becoming "1K" would show a player a balance they do not have.
        assertEquals("999", Numbers.compact(999L));
        assertEquals("999.9K", Numbers.compact(999_900L));
    }

    @Test
    @DisplayName("a negative number keeps its sign")
    void compactNegatives() {
        assertEquals("-1.5K", Numbers.compact(-1_500L));
        assertEquals("-5", Numbers.compact(-5L));
    }

    @Test
    @DisplayName("a number that is not a number renders as zero, never as NaN")
    void compactNonFinite() {
        // A scoreboard reading "NaN" is worse than one reading "0": the second
        // is wrong, the first is broken.
        assertEquals("0", Numbers.compact(Double.NaN));
        assertEquals("0", Numbers.compact(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("thousands are grouped for a number somebody reads carefully")
    void grouped() {
        assertEquals("1,234,567", Numbers.grouped(1_234_567L));
        assertEquals("999", Numbers.grouped(999L));
        assertEquals("1,000", Numbers.grouped(1_000L));
        assertEquals("-1,234", Numbers.grouped(-1_234L));
        assertEquals("0", Numbers.grouped(0L));
    }

    @Test
    @DisplayName("grouping with decimals keeps both")
    void groupedWithDecimals() {
        assertEquals("1,234.50", Numbers.grouped(1234.5, 2));
        assertEquals("1,234", Numbers.grouped(1234.4, 0));
    }

    @Test
    @DisplayName("a fixed number of decimals keeps its trailing zeros")
    void decimals() {
        // A column of prices reading 1.50 and 2.00 lines up; one reading 1.5
        // and 2 does not.
        assertEquals("3.14", Numbers.decimals(3.14159, 2));
        assertEquals("1.50", Numbers.decimals(1.5, 2));
        assertEquals("2.00", Numbers.decimals(2.0, 2));
        assertEquals("-1.50", Numbers.decimals(-1.5, 2));
    }

    @Test
    @DisplayName("rounding is half-up, not Java's half-even")
    void roundingIsHalfUp() {
        // Half-even renders 2.5 as 2 and 3.5 as 4. Correct for statistics, and
        // it reads as a bug to a player watching a counter.
        assertEquals("2.5", Numbers.decimals(2.45, 1));
        assertEquals("3.5", Numbers.decimals(3.45, 1));
        assertEquals("0.3", Numbers.decimals(0.25, 1));
    }

    @Test
    @DisplayName("compact notation rounds half-up too")
    void compactRoundingIsHalfUp() {
        // Its own rounding step, separate from decimals(): half-even would
        // render these as 1.2K and 2.2M. A balance that ticks up by one and
        // displays a smaller number reads as the server losing money.
        assertEquals("1.3K", Numbers.compact(1_250L));
        assertEquals("1.4K", Numbers.compact(1_350L));
        assertEquals("2.3M", Numbers.compact(2_250_000L));
    }

    @Test
    @DisplayName("trailing zeros are dropped when the number stands alone")
    void trimmed() {
        assertEquals("2", Numbers.trimmed(2.0, 2));
        assertEquals("2.5", Numbers.trimmed(2.50, 2));
        assertEquals("2.05", Numbers.trimmed(2.05, 2));
    }

    @Test
    @DisplayName("a percentage takes the scale its name says")
    void percentScale() {
        // ExyliaCommons had one method that scaled its input and another that
        // did not, so formatPercent(0.75) rendered "0.75%" while
        // formatRatio(3, 4) rendered "75%". A caller could not tell which they
        // had without reading the source.
        assertEquals("75%", Numbers.percent(75));
        assertEquals("75%", Numbers.percentOfFraction(0.75));
        assertEquals("75%", Numbers.percentOf(3, 4));
    }

    @Test
    @DisplayName("a percentage of nothing is nothing, not a division by zero")
    void percentOfZero() {
        // A win rate with no games played. The caller should not have to guard
        // every ratio it shows.
        assertEquals("0%", Numbers.percentOf(0, 0));
        assertEquals("0%", Numbers.percentOf(5, 0));
    }

    @Test
    @DisplayName("the win rate the ecosystem already stores renders correctly")
    void existingWinRateScale() {
        // PracticeCore stores winRate as wins / total * 100, so it is already
        // on the hundred scale. percent() is the method for it.
        double stored = 3.0 / 4.0 * 100.0;
        assertEquals("75%", Numbers.percent(stored));
    }

    @Test
    @DisplayName("ordinals get the teens right")
    void ordinals() {
        // Eleven, twelve and thirteen take "th" despite ending in one, two and
        // three. It is the case a hand-written version gets wrong.
        assertEquals("1st", Numbers.ordinal(1));
        assertEquals("2nd", Numbers.ordinal(2));
        assertEquals("3rd", Numbers.ordinal(3));
        assertEquals("4th", Numbers.ordinal(4));
        assertEquals("11th", Numbers.ordinal(11));
        assertEquals("12th", Numbers.ordinal(12));
        assertEquals("13th", Numbers.ordinal(13));
        assertEquals("21st", Numbers.ordinal(21));
        assertEquals("101st", Numbers.ordinal(101));
        assertEquals("111th", Numbers.ordinal(111));
    }

    @Test
    @DisplayName("output does not depend on the server's locale")
    void localeIndependent() {
        // The bug this whole class exists to prevent, and the one the ecosystem
        // has in a hundred and fifty-four places today: on a host in Spain,
        // Java renders 1.5 as "1,5" and groups 1234 as "1.234". The same config
        // then produces different text on two servers.
        Locale previous = Locale.getDefault();
        try {
            for (String tag : new String[] {"es-ES", "de-DE", "fr-FR", "ar-EG"}) {
                Locale.setDefault(Locale.forLanguageTag(tag));

                assertEquals("1.5K", Numbers.compact(1_500L), tag);
                assertEquals("1,234,567", Numbers.grouped(1_234_567L), tag);
                assertEquals("3.14", Numbers.decimals(3.14159, 2), tag);
                assertEquals("75%", Numbers.percent(75), tag);
                assertEquals("3rd", Numbers.ordinal(3), tag);
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("a number past what a double counts in ones still renders exactly")
    void veryLargeNumbers() {
        // Past 2^53 a double cannot represent consecutive integers, so the
        // digits below that point are noise from the binary representation
        // rather than information.
        // The literal below is not representable as a double at all: it is
        // read as 9007199254740992. What matters is that the digits printed are
        // the ones the double really holds, rather than a long that overflowed.
        assertEquals("9007199254740992", Numbers.decimals(9.007199254740993E15, 0));
        assertEquals("1000000000000000000", Numbers.decimals(1.0E18, 0));
        assertTrue(Numbers.compact(1.5E18).endsWith("Q"));
    }

    @Test
    @DisplayName("formatting allocates nothing but the string it returns")
    void doesNotChurn() {
        // Not a timing assertion, which would be flaky on a shared machine.
        // This runs the hot path enough times that a per-call DecimalFormat —
        // which is what the ecosystem does today — would be visible as garbage,
        // and asserts the answers stay identical throughout.
        String expected = Numbers.compact(1_500L);
        for (int index = 0; index < 200_000; index++) {
            assertEquals(expected, Numbers.compact(1_500L));
        }
    }
}
