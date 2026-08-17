package net.exylia.lib.format;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link Formats} promises a server owner and a plugin author.
 *
 * <p>The documented defaults, the effect of every setting, and the three ways
 * this could quietly produce the wrong text: a {@code double} losing a cent, a
 * European default locale turning a decimal point into a comma, and a reload
 * that applies to some calls but not others.
 */
class FormatsTest {

    @AfterEach
    void tearDown() {
        // Static state: a test that changed the symbol must not decide what the
        // next one sees.
        Formats.reset();
    }

    // ------------------------------------------------------------------
    // The documented defaults
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("with the defaults a fresh formats.yml contains")
    class Defaults {

        @Test
        @DisplayName("money is grouped, has two decimals and leads with $")
        void money() {
            assertEquals("$1,250.00", Formats.money(1250L));
            assertEquals("$0.00", Formats.money(0L));
            assertEquals("$999,999.00", Formats.money(999_999L));
            assertEquals("$12.50", Formats.money(new BigDecimal("12.5")));
        }

        @Test
        @DisplayName("a negative amount keeps its sign in front of the symbol")
        void negativeMoney() {
            // -$5.00, never $-5.00: the second is not how any bank statement in
            // the world writes it, and a player reading a menu should not have
            // to work out where the minus went.
            assertEquals("-$5.00", Formats.money(-5L));
        }

        @Test
        @DisplayName("money past a million is shortened")
        void compactMoney() {
            assertEquals("$2.5M", Formats.money(2_500_000L));
            assertEquals("$1M", Formats.money(1_000_000L));
            // Just below the threshold it stays exact, so a price a player has
            // to approve never hides what they are about to pay.
            assertEquals("$999,999.00", Formats.money(999_999L));
        }

        @Test
        @DisplayName("a large number is shortened with an uppercase suffix")
        void compact() {
            assertEquals("1.5K", Formats.compact(1_500L));
            assertEquals("2K", Formats.compact(2_000L));
            assertEquals("2.3M", Formats.compact(2_340_000L));
            assertEquals("1.5B", Formats.compact(1_500_000_000L));
        }

        @Test
        @DisplayName("below the threshold a number is written out, grouped")
        void belowCompactThreshold() {
            assertEquals("999", Formats.compact(999L));
            assertEquals("0", Formats.compact(0L));
        }

        @Test
        @DisplayName("a percentage drops decimals that say nothing")
        void percent() {
            assertEquals("75%", Formats.percent(75));
            assertEquals("75.5%", Formats.percent(75.5));
            assertEquals("0%", Formats.percent(0));
            assertEquals("-3%", Formats.percent(-3));
        }

        @Test
        @DisplayName("a percentage from a part and a whole")
        void percentOf() {
            assertEquals("75%", Formats.percentOf(3, 4));
            // No games played is nothing, not a division by zero the caller has
            // to guard before every call.
            assertEquals("0%", Formats.percentOf(0, 0));
        }

        @Test
        @DisplayName("a date is written day first")
        void date() {
            long stamp = java.time.LocalDateTime.of(2026, 8, 17, 14, 30)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
            assertEquals("17/08/2026", Formats.date(stamp));
        }

        @Test
        @DisplayName("relative text says which direction it went")
        void relative() {
            long now = System.currentTimeMillis();
            assertTrue(Formats.relative(now - 3 * 86_400_000L).endsWith(" ago"),
                    "the past should read as ago");
            assertTrue(Formats.relative(now + 7_200_000L).startsWith("in "),
                    "the future should read as in");
            assertEquals("just now", Formats.relative(now));
        }
    }

    // ------------------------------------------------------------------
    // Every setting actually does something
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("changing a setting changes the output everywhere")
    class Settings {

        @Test
        @DisplayName("the currency symbol")
        void symbol() {
            apply(money(new FormatSettings.Money(
                    "coins", FormatSettings.SymbolPosition.AFTER, true, 0, false, 1_000_000L)));

            assertEquals("1,250 coins", Formats.money(1250L));
        }

        @Test
        @DisplayName("the symbol position, with the sign still leading")
        void symbolPosition() {
            apply(money(new FormatSettings.Money(
                    "€", FormatSettings.SymbolPosition.AFTER, true, 2, false, 1_000_000L)));

            assertEquals("1,250.00 €", Formats.money(1250L));
            assertEquals("-5.00 €", Formats.money(-5L));
        }

        @Test
        @DisplayName("the number of money decimals")
        void moneyDecimals() {
            apply(money(new FormatSettings.Money(
                    "$", FormatSettings.SymbolPosition.BEFORE, false, 0, true, 1_000_000L)));

            assertEquals("$1,250", Formats.money(1250L));
            // Rounding is half-up, not Java's half-even: a balance that shows
            // one coin less than the player counted is a support ticket.
            assertEquals("$13", Formats.money(new BigDecimal("12.5")));
        }

        @Test
        @DisplayName("turning off compact money writes the amount out")
        void moneyCompactOff() {
            apply(money(new FormatSettings.Money(
                    "$", FormatSettings.SymbolPosition.BEFORE, false, 2, false, 1_000_000L)));

            assertEquals("$2,500,000.00", Formats.money(2_500_000L));
        }

        @Test
        @DisplayName("the compact threshold decides where shortening starts")
        void moneyCompactThreshold() {
            apply(money(new FormatSettings.Money(
                    "$", FormatSettings.SymbolPosition.BEFORE, false, 2, true, 1_000L)));

            assertEquals("$1.5K", Formats.money(1_500L));
        }

        @Test
        @DisplayName("lowercase suffixes")
        void lowercaseSuffixes() {
            apply(compact(new FormatSettings.Compact(1, true, 1_000L)));

            assertEquals("1.5k", Formats.compact(1_500L));
            assertEquals("2.3m", Formats.compact(2_340_000L));
        }

        @Test
        @DisplayName("compact decimals")
        void compactDecimals() {
            apply(compact(new FormatSettings.Compact(2, false, 1_000L)));

            assertEquals("1.23K", Formats.compact(1_234L));

            apply(compact(new FormatSettings.Compact(0, false, 1_000L)));

            assertEquals("1K", Formats.compact(1_234L));
        }

        @Test
        @DisplayName("the compact threshold")
        void compactThreshold() {
            apply(compact(new FormatSettings.Compact(1, false, 1_000_000L)));

            assertEquals("12,345", Formats.compact(12_345L));
            assertEquals("1.5M", Formats.compact(1_500_000L));
        }

        @Test
        @DisplayName("percent decimals")
        void percentDecimals() {
            apply(percent(new FormatSettings.Percent(0, false)));
            assertEquals("76%", Formats.percent(75.6));

            apply(percent(new FormatSettings.Percent(3, false)));
            assertEquals("75.555%", Formats.percent(75.5551));
        }

        @Test
        @DisplayName("a plus sign on positives, and never on zero")
        void percentPlus() {
            apply(percent(new FormatSettings.Percent(1, true)));

            assertEquals("+12.5%", Formats.percent(12.5));
            assertEquals("-3%", Formats.percent(-3));
            // "+0%" beside a "0%" produced elsewhere is a difference nobody can
            // explain, so a zero never gets the sign.
            assertEquals("0%", Formats.percent(0));
        }

        @Test
        @DisplayName("the date style")
        void dateStyle() {
            long stamp = java.time.LocalDateTime.of(2026, 8, 17, 14, 30)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();

            apply(date(new FormatSettings.Date(Dates.Style.ISO)));
            assertEquals("2026-08-17", Formats.date(stamp));

            apply(date(new FormatSettings.Date(Dates.Style.FULL)));
            assertEquals("Monday, 17 August 2026", Formats.date(stamp));
        }
    }

    // ------------------------------------------------------------------
    // Money precision
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("money keeps every cent")
    class Precision {

        @Test
        @DisplayName("BigDecimal arithmetic does not drift where a double would")
        void bigDecimalDoesNotDrift() {
            // The canonical case: in a double this sum is 0.30000000000000004,
            // and a shop that adds three prices that way shows a total a player
            // can prove wrong.
            assertNotEquals(0.3, 0.1 + 0.2, "the premise of this test");

            BigDecimal exact = new BigDecimal("0.1").add(new BigDecimal("0.2"));
            assertEquals("$0.30", Formats.money(exact));
        }

        @Test
        @DisplayName("a hundred additions of a cent are still a euro")
        void repeatedAddition() {
            BigDecimal total = BigDecimal.ZERO;
            for (int index = 0; index < 100; index++) {
                total = total.add(new BigDecimal("0.01"));
            }
            assertEquals("$1.00", Formats.money(total));

            double drifting = 0;
            for (int index = 0; index < 100; index++) {
                drifting += 0.01;
            }
            assertNotEquals(1.0, drifting, "the premise of this test");
        }

        @Test
        @DisplayName("an amount larger than a long survives")
        void beyondLong() {
            apply(money(new FormatSettings.Money(
                    "$", FormatSettings.SymbolPosition.BEFORE, false, 2, false, 1_000_000L)));

            BigDecimal huge = new BigDecimal("123456789012345678901.23");
            assertEquals("$123,456,789,012,345,678,901.23", Formats.money(huge));
        }

        @Test
        @DisplayName("a double is read as the decimal it prints, not its binary noise")
        void doubleReadsAsPrinted() {
            // 1.005 is not exactly representable: the nearest double is
            // 1.00499999999999989..., so rounding the exact binary value to two
            // places gives 1.00 while rounding the decimal the user wrote gives
            // 1.01. BigDecimal.valueOf reads the shortest decimal that
            // round-trips, which is the one they typed.
            assertTrue(new BigDecimal(1.005).compareTo(new BigDecimal("1.005")) < 0,
                    "the premise of this test");

            assertEquals("$1.01", Formats.money(1.005));
            assertEquals("$2.68", Formats.money(2.675));
        }

        @Test
        @DisplayName("a compact amount is shortened without leaving BigDecimal")
        void compactKeepsPrecision() {
            assertEquals("$1.2M", Formats.money(new BigDecimal("1234567.89")));
        }

        @Test
        @DisplayName("a balance that is not a number does not print NaN")
        void notANumber() {
            assertEquals("$0.00", Formats.money(Double.NaN));
            assertEquals("$0.00", Formats.money(Double.POSITIVE_INFINITY));
        }

        @Test
        @DisplayName("the smallest long does not flip its sign")
        void longMinValue() {
            // Math.abs(Long.MIN_VALUE) is itself, still negative. Anything that
            // took the sign off that way would print a negative number after
            // having already printed a minus sign.
            assertTrue(Formats.money(Long.MIN_VALUE).startsWith("-"),
                    "should still read as negative");
            assertTrue(Formats.compact(Long.MIN_VALUE).startsWith("-"),
                    "should still read as negative");
        }
    }

    // ------------------------------------------------------------------
    // Reload
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("applying settings again")
    class Reload {

        @Test
        @DisplayName("takes effect on the next call, with no restart")
        void reapplies() {
            assertEquals("$1,250.00", Formats.money(1250L));

            apply(money(new FormatSettings.Money(
                    "€", FormatSettings.SymbolPosition.AFTER, true, 2, false, 1_000_000L)));

            assertEquals("1,250.00 €", Formats.money(1250L));
        }

        @Test
        @DisplayName("puts the defaults back")
        void resets() {
            apply(money(new FormatSettings.Money(
                    "€", FormatSettings.SymbolPosition.AFTER, true, 2, true, 1L)));
            // Shortening follows the compact section, so this keeps one decimal
            // rather than money's two: a shortened figure is a glance, and the
            // section that decides what a glance looks like is the same one a
            // kill count uses.
            assertEquals("1.3K €", Formats.money(1250L));

            Formats.reset();

            assertEquals("$1,250.00", Formats.money(1250L));
        }

        @Test
        @DisplayName("publishes settings whole, never half applied")
        void appliesAtomically() {
            FormatSettings applied = new FormatSettings(
                    new FormatSettings.Money("£", FormatSettings.SymbolPosition.BEFORE,
                            false, 3, false, 1_000_000L),
                    new FormatSettings.Compact(),
                    new FormatSettings.Percent(),
                    new FormatSettings.Date());
            Formats.apply(applied);

            // The symbol and the decimal count came from the same record: a
            // reader can never see one without the other.
            assertEquals("£1,250.000", Formats.money(1250L));
            assertEquals("£", Formats.settings().money().symbol());
        }
    }

    // ------------------------------------------------------------------
    // Nonsense in the file
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a value that makes no sense is clamped, not thrown")
    class Clamping {

        @Test
        @DisplayName("an absurd decimal count does not take a menu down")
        void absurdDecimals() {
            apply(money(new FormatSettings.Money(
                    "$", FormatSettings.SymbolPosition.BEFORE, false, 3000, false, 1_000_000L)));

            // A slightly odd number is a better outcome than a server that will
            // not start over a cosmetic setting.
            assertEquals("$1,250.00000000", Formats.money(1250L));
        }

        @Test
        @DisplayName("a negative decimal count is read as none")
        void negativeDecimals() {
            apply(percent(new FormatSettings.Percent(-4, false)));

            assertEquals("76%", Formats.percent(75.6));
        }

        @Test
        @DisplayName("a compact threshold below a thousand is raised to one")
        void thresholdBelowSmallestSuffix() {
            // There is no suffix for a hundred, so a lower threshold would
            // silently do nothing and leave an owner adjusting a dead setting.
            apply(compact(new FormatSettings.Compact(1, false, 10L)));

            assertEquals("999", Formats.compact(999L));
            assertEquals("1K", Formats.compact(1_000L));
        }
    }

    // ------------------------------------------------------------------
    // Locale independence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a European default locale does not change a single character")
    void localeIndependent() {
        Locale original = Locale.getDefault();
        try {
            // On a host in Spain, Java's own formatting renders 1,250.00 as
            // 1.250,00 and 1.5K as 1,5K. Two servers running the same config
            // would show different text, and neither is wrong from Java's point
            // of view — which is what makes it impossible to see coming.
            Locale.setDefault(Locale.forLanguageTag("es-ES"));

            assertEquals("$1,250.00", Formats.money(1250L));
            assertEquals("$1,250.50", Formats.money(new BigDecimal("1250.5")));
            assertEquals("1.5K", Formats.compact(1_500L));
            assertEquals("75.5%", Formats.percent(75.5));

            Locale.setDefault(Locale.GERMANY);

            assertEquals("$1,250.00", Formats.money(1250L));
            assertEquals("2.3M", Formats.compact(2_340_000L));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void apply(FormatSettings settings) {
        Formats.apply(settings);
    }

    private static FormatSettings money(FormatSettings.Money money) {
        return new FormatSettings(money, new FormatSettings.Compact(),
                new FormatSettings.Percent(), new FormatSettings.Date());
    }

    private static FormatSettings compact(FormatSettings.Compact compact) {
        return new FormatSettings(new FormatSettings.Money(), compact,
                new FormatSettings.Percent(), new FormatSettings.Date());
    }

    private static FormatSettings percent(FormatSettings.Percent percent) {
        return new FormatSettings(new FormatSettings.Money(), new FormatSettings.Compact(),
                percent, new FormatSettings.Date());
    }

    private static FormatSettings date(FormatSettings.Date date) {
        return new FormatSettings(new FormatSettings.Money(), new FormatSettings.Compact(),
                new FormatSettings.Percent(), date);
    }
}
