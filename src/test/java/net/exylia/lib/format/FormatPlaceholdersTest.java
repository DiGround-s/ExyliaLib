package net.exylia.lib.format;

import net.exylia.lib.FakeServer;
import net.exylia.lib.format.internal.FormatPlaceholders;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.internal.Registry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format module reached from a config file, which is the point of it: a
 * server owner writes a menu that formats a number without anybody writing Java.
 */
class FormatPlaceholdersTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        plugin = FakeServer.newPlugin("FormatTestPlugin", null);
        Registry.clear();
        Logger quiet = Logger.getLogger("FormatPlaceholderTest");
        quiet.setLevel(Level.OFF);
        Placeholders.logger(quiet);
        FormatPlaceholders.register(plugin);
    }

    @AfterEach
    void tearDown() {
        Registry.clear();
        Formats.reset();
    }

    // ------------------------------------------------------------------
    // Each placeholder produces the documented text
    // ------------------------------------------------------------------

    @Test
    @DisplayName("%exylia_money_<n>% formats an amount")
    void money() {
        assertEquals("Balance: $1,250.00", Placeholders.apply("Balance: %exylia_money_1250%"));
    }

    @Test
    @DisplayName("%exylia_compact_<n>% shortens a number")
    void compact() {
        assertEquals("Kills: 1.5K", Placeholders.apply("Kills: %exylia_compact_1500%"));
    }

    @Test
    @DisplayName("%exylia_percent_<n>% writes a percentage")
    void percent() {
        assertEquals("Win rate: 75%", Placeholders.apply("Win rate: %exylia_percent_75%"));
    }

    @Test
    @DisplayName("%exylia_ordinal_<n>% writes a place in a ranking")
    void ordinal() {
        assertEquals("3rd", Placeholders.apply("%exylia_ordinal_3%"));
        // The teens are the case a hand-written version gets wrong.
        assertEquals("11th", Placeholders.apply("%exylia_ordinal_11%"));
    }

    @Test
    @DisplayName("%exylia_relative_<millis>% says how long ago")
    void relative() {
        long threeDaysAgo = System.currentTimeMillis() - 3 * 86_400_000L;
        assertTrue(Placeholders.apply("%exylia_relative_" + threeDaysAgo + "%").endsWith(" ago"),
                "should read as being in the past");
    }

    @Test
    @DisplayName("%exylia_date_<millis>% writes a date in the configured style")
    void date() {
        long stamp = java.time.LocalDateTime.of(2026, 8, 17, 14, 30)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        assertEquals("17/08/2026", Placeholders.apply("%exylia_date_" + stamp + "%"));
    }

    // ------------------------------------------------------------------
    // They follow the settings, like everything else in the module
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reload reaches the placeholders too")
    void followsSettings() {
        assertEquals("$1,250.00", Placeholders.apply("%exylia_money_1250%"));

        Formats.apply(new FormatSettings(
                new FormatSettings.Money("€", FormatSettings.SymbolPosition.AFTER,
                        true, 0, false, 1_000_000L),
                new FormatSettings.Compact(1, true, 1_000L),
                new FormatSettings.Percent(1, true),
                new FormatSettings.Date(Dates.Style.ISO)));

        assertEquals("1,250 €", Placeholders.apply("%exylia_money_1250%"));
        assertEquals("1.5k", Placeholders.apply("%exylia_compact_1500%"));
        assertEquals("+75%", Placeholders.apply("%exylia_percent_75%"));
    }

    // ------------------------------------------------------------------
    // What happens when the argument is not a number
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unresolved argument leaves the placeholder visible")
    void nonNumericStaysVisible() {
        // Not "$0.00": that looks exactly like a player with no money, and the
        // missing economy plugin behind it would go unnoticed until somebody
        // rich complained.
        assertEquals("%exylia_money_abc%", Placeholders.apply("%exylia_money_abc%"));
    }

    @Test
    @DisplayName("a config's own fallback still applies")
    void fallbackApplies() {
        assertEquals("none", Placeholders.apply("%exylia_money_abc|none%"));
    }

    @Test
    @DisplayName("a missing argument is not read as zero")
    void missingArgument() {
        assertEquals("%exylia_money%", Placeholders.apply("%exylia_money%"));
    }

    @Test
    @DisplayName("a timestamp does not lose precision on the way in")
    void timestampStaysWhole() {
        // Parsed as a long, not a double: a double stops counting in ones past
        // about nine quadrillion, and a millisecond stamp is sixteen digits.
        long stamp = 1_755_400_000_123L;
        assertEquals(Dates.formatMillis(stamp, Dates.Style.DATE),
                Placeholders.apply("%exylia_date_" + stamp + "%"));
    }

    // ------------------------------------------------------------------
    // Locale
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a European default locale does not change the rendered text")
    void localeIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"));

            assertEquals("$1,250.00", Placeholders.apply("%exylia_money_1250%"));
            assertEquals("1.5K", Placeholders.apply("%exylia_compact_1500%"));
            assertEquals("75.5%", Placeholders.apply("%exylia_percent_75.5%"));
        } finally {
            Locale.setDefault(original);
        }
    }

    // ------------------------------------------------------------------
    // Ownership
    // ------------------------------------------------------------------

    @Test
    @DisplayName("everything is registered under the library, so it unloads with it")
    void ownedByTheLibrary() {
        assertTrue(Placeholders.has("exylia_money"));
        assertTrue(Placeholders.has("exylia_relative"));

        Placeholders.unregisterAll(plugin.getName());

        assertEquals("%exylia_money_1250%", Placeholders.apply("%exylia_money_1250%"));
    }
}
