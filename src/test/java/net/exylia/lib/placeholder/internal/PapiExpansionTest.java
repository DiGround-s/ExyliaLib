package net.exylia.lib.placeholder.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PapiExpansionTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Registry.clear();
        plugin = FakeServer.newPlugin("ExyliaFFA");
    }

    @AfterEach
    void tearDown() {
        Registry.clear();
        Loggers.set(Logger.getLogger("ExyliaLib"));
    }

    @Test
    void mapsThePapiIdentifierToTheOwnedPlaceholderNames() {
        Placeholders.register(plugin, "total_players", request -> 42);

        PapiExpansion expansion = new PapiExpansion(plugin);

        assertEquals(List.of("total_players"), expansion.getPlaceholders());
        assertEquals("42", expansion.onRequest(null, "total_players"));
    }

    @Test
    void resolvesParamsWithoutRepeatingThePapiIdentifier() {
        Placeholders.group(plugin, "top").add("player", request -> request.arg(0, 0)).register();

        assertEquals("3", new PapiExpansion(plugin).onRequest(null, "top_player_3"));
    }

    /**
     * The reported scenario, with ExyliaFFA holding the name: its expansion
     * answers with its own value, and the other plugin's expansion does not
     * answer at all rather than handing ExyliaFFA's number out under its own
     * identifier.
     */
    @Test
    void answersWithItsOwnPluginsValueWhenTwoPluginsShareAName() {
        Plugin other = FakeServer.newPlugin("ExyliaSandBox");
        Placeholders.register(other, "total_players", request -> 0);
        // Registered last, so ExyliaFFA holds the flat registry slot.
        Placeholders.register(plugin, "total_players", request -> 42);

        assertEquals("42", new PapiExpansion(plugin).onRequest(null, "total_players"));
        assertNull(new PapiExpansion(other).onRequest(null, "total_players"));
    }

    /**
     * The exact reported defect, in its reported order: ExyliaSandBox enabled
     * last and took the slot, so {@code %exyliaffa_total_players%} used to run
     * ExyliaSandBox's resolver and return its real {@code 0}. It must return
     * nothing instead, leaving the text visible.
     */
    @Test
    void doesNotAnswerWithAnotherPluginsValue() {
        Plugin other = FakeServer.newPlugin("ExyliaSandBox");
        Placeholders.register(plugin, "total_players", request -> 42);
        Placeholders.register(other, "total_players", request -> 0);

        assertNull(new PapiExpansion(plugin).onRequest(null, "total_players"));
        assertEquals("0", new PapiExpansion(other).onRequest(null, "total_players"));
    }

    @Test
    void doesNotAnswerForAnUnregisteredName() {
        assertNull(new PapiExpansion(plugin).onRequest(null, "nobody_registered_this"));
    }

    /**
     * Arguments are split off by longest owned prefix, so a name another plugin
     * owns cannot be mistaken for this plugin's placeholder plus an argument.
     */
    @Test
    void resolvesArgumentsAgainstItsOwnPrefixOnly() {
        Plugin other = FakeServer.newPlugin("ExyliaSandBox");
        Placeholders.group(other, "stats").add("top", request -> "wrong").register();
        Placeholders.group(plugin, "stats").add("top_kills", request -> "kills#" + request.arg(0, 0)).register();

        assertEquals("kills#1", new PapiExpansion(plugin).onRequest(null, "stats_top_kills_1"));
        assertNull(new PapiExpansion(plugin).onRequest(null, "stats_top_2"));
    }

    @Test
    void warnsOnceWhenADifferentPluginTakesOverAName() {
        Plugin other = FakeServer.newPlugin("ExyliaSandBox");
        List<String> warnings = new java.util.ArrayList<>();
        Logger probe = Logger.getLogger("OverwriteProbe_" + System.nanoTime());
        probe.setUseParentHandlers(false);
        probe.addHandler(new java.util.logging.Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Loggers.set(probe);

        Placeholders.register(plugin, "total_players", request -> 42);
        // A reload of the same plugin is documented behaviour and stays silent.
        Placeholders.register(plugin, "total_players", request -> 43);
        assertEquals(List.of(), warnings);

        Placeholders.register(other, "total_players", request -> 0);
        Placeholders.register(other, "total_players", request -> 1);
        // Taken back and forth again: still the same name, still one warning.
        Placeholders.register(plugin, "total_players", request -> 44);
        Placeholders.register(other, "total_players", request -> 2);

        assertEquals(1, warnings.size(), "reported once per name, not per takeover");
        String warning = warnings.get(0);
        assertTrue(warning.contains("total_players"), warning);
        assertTrue(warning.contains("ExyliaFFA"), warning);
        assertTrue(warning.contains("ExyliaSandBox"), warning);
    }
}
