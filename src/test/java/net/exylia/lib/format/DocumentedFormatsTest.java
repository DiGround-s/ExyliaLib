package net.exylia.lib.format;

import net.exylia.lib.FakeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claims {@code docs/formats.md} and {@code formats.yml} make, executed.
 *
 * <p>Every worked example written in a comment above a setting is a promise to
 * a server owner: they read {@code # These defaults produce: $1,250.00}, decide
 * the file needs no editing, and never check. A comment that lies is worse than
 * no comment, because it is believed.
 */
class DocumentedFormatsTest {

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Formats.reset();
    }

    @AfterEach
    void tearDown() {
        Formats.reset();
    }

    @Test
    @DisplayName("formats.yml: \"These defaults produce: $1,250.00\"")
    void moneyDefaultExample() {
        assertEquals("$1,250.00", Formats.money(1250L));
        assertEquals("$1,250.00", Formats.money(new BigDecimal("1250")));
    }

    @Test
    @DisplayName("formats.yml: \"and, past the threshold below, $2.5M\"")
    void moneyCompactExample() {
        assertEquals("$2.5M", Formats.money(2_500_000L));
    }

    @Test
    @DisplayName("formats.yml: \"a price a player has to approve stays exact: 999,999.00\"")
    void belowThresholdStaysExact() {
        // The claim that justifies the threshold existing. A confirmation
        // screen reading "1M" for an amount that is not one million is how
        // somebody approves a purchase they did not agree to.
        assertEquals("$999,999.00", Formats.money(999_999L));
    }

    @Test
    @DisplayName("formats.yml: \"These defaults produce: 1.5K, 2.3M, 1.5B\"")
    void compactDefaultExample() {
        assertEquals("1.5K", Formats.compact(1_500L));
        assertEquals("2.3M", Formats.compact(2_340_000L));
        assertEquals("1.5B", Formats.compact(1_500_000_000L));
    }

    @Test
    @DisplayName("formats.yml: \"These defaults produce: 75% and 75.5%\"")
    void percentDefaultExample() {
        assertEquals("75%", Formats.percent(75));
        assertEquals("75.5%", Formats.percent(75.5));
    }

    @Test
    @DisplayName("formats.yml: \"2000 is 2K, never 2.0K\"")
    void trailingZerosDropped() {
        assertEquals("2K", Formats.compact(2_000L));
    }

    @Test
    @DisplayName("formats.yml: \"-$5.00, never $-5.00\"")
    void negativeKeepsSignInFront() {
        // A minus sign after the currency symbol reads as part of the number
        // and is easy to miss entirely on a scoreboard.
        assertEquals("-$5.00", Formats.money(-5L));
    }

    @Test
    @DisplayName("formats.yml: \"999 reads 999 and 12345 reads 12.3K; raise the threshold and it reads 12,345\"")
    void belowCompactThreshold() {
        assertEquals("999", Formats.compact(999L));
        assertEquals("12.3K", Formats.compact(12_345L));

        Formats.apply(new FormatSettings(
                new FormatSettings.Money(),
                new FormatSettings.Compact(1, false, 100_000L),
                new FormatSettings.Percent(),
                new FormatSettings.Date()));
        assertEquals("12,345", Formats.compact(12_345L));
    }

    @Test
    @DisplayName("changing a setting changes every plugin's output at once")
    void settingsTakeEffect() {
        // The whole reason the module exists: one file, not two thousand.
        FormatSettings euros = new FormatSettings(
                new FormatSettings.Money("€", FormatSettings.SymbolPosition.AFTER,
                        true, 2, false, 1_000_000L),
                new FormatSettings.Compact(1, true, 1_000L),
                new FormatSettings.Percent(0, true),
                new FormatSettings.Date(Dates.Style.ISO));
        Formats.apply(euros);

        assertEquals("1,250.00 €", Formats.money(1250L));
        assertEquals("1.5k", Formats.compact(1_500L));
        assertEquals("+75%", Formats.percent(75));
    }

    @Test
    @DisplayName("money keeps precision a double would have lost")
    void moneyPrecision() {
        // 1.005 is the case that separates a real BigDecimal path from one that
        // only looks like it: the binary double nearest 1.005 is slightly below
        // it, so a double rounds to 1.00 while the decimal the player typed
        // rounds to 1.01.
        assertEquals("$1.01", Formats.money(new BigDecimal("1.005")));

        BigDecimal sum = new BigDecimal("0.1")
                .add(new BigDecimal("0.2"));
        assertEquals("$0.30", Formats.money(sum));
    }

    @Test
    @DisplayName("a broken number renders as zero, never as NaN")
    void nonFinite() {
        // A menu showing "$NaN" is a support ticket about the wrong plugin.
        assertEquals("$0.00", Formats.money(Double.NaN));
        assertEquals("0", Formats.compact(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("output does not depend on the host's locale")
    void localeIndependent() {
        // Java's own grouping on a host in Spain renders 1250 as "1.250" and
        // 1.5 as "1,5". Two servers running the same config would disagree, and
        // neither would look wrong from the inside.
        Locale previous = Locale.getDefault();
        try {
            for (String tag : new String[] {"es-ES", "de-DE", "fr-FR"}) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                assertEquals("$1,250.00", Formats.money(1250L), tag);
                assertEquals("1.5K", Formats.compact(1_500L), tag);
                assertEquals("75.5%", Formats.percent(75.5), tag);
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("a reload is atomic: never the new symbol beside the old decimals")
    void reloadIsAtomic() throws InterruptedException {
        // The reason the settings are published as one immutable object rather
        // than mutated field by field. A render that caught the change halfway
        // would produce a string that no configuration ever described.
        FormatSettings other = new FormatSettings(
                new FormatSettings.Money("€", FormatSettings.SymbolPosition.AFTER,
                        true, 0, false, 1_000_000L),
                new FormatSettings.Compact(1, false, 1_000L),
                new FormatSettings.Percent(1, false),
                new FormatSettings.Date(Dates.Style.DATE));

        Thread reloader = new Thread(() -> {
            for (int index = 0; index < 500; index++) {
                Formats.apply(index % 2 == 0 ? other : new FormatSettings());
            }
        });
        reloader.start();

        for (int index = 0; index < 5_000; index++) {
            String rendered = Formats.money(1250L);
            assertTrue(rendered.equals("$1,250.00") || rendered.equals("1,250 €"),
                    "half-applied settings produced: " + rendered);
        }
        reloader.join();
    }

    @Test
    @DisplayName("a plugin formatting before the config is read gets the defaults")
    void defaultsBeforeLoad() {
        // Startup order is not something a consumer controls, and an exception
        // from a formatter during onEnable reads as a bug in the consumer.
        Formats.reset();
        assertEquals("$1,250.00", Formats.money(1250L));
    }

    @Test
    @DisplayName("Numbers stays fixed while Formats follows the config")
    void numbersIsNotConfigurable() {
        // The split that makes Numbers safe to use for a map key or a value
        // written to a file: what a server owner does to formats.yml must not
        // change what a stored string looks like.
        Formats.apply(new FormatSettings(
                new FormatSettings.Money("€", FormatSettings.SymbolPosition.AFTER,
                        true, 2, false, 1_000_000L),
                new FormatSettings.Compact(1, true, 1_000L),
                new FormatSettings.Percent(1, false),
                new FormatSettings.Date(Dates.Style.ISO)));

        assertEquals("1.5K", Numbers.compact(1_500L), "Numbers must not move");
        assertNotEquals(Numbers.compact(1_500L), Formats.compact(1_500L),
                "Formats must follow the config");
    }
}
