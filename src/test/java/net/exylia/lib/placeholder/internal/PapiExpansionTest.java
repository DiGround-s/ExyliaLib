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
     * The reported defect: {@code %exyliaffa_total_players%} and
     * {@code %exyliasandbox_total_players%} are two different placeholders,
     * because the identifier in front of them says so. Both plugins registering
     * the name {@code total_players} must therefore both keep answering, in
     * whichever order they enabled.
     */
    @Test
    void bothPluginsAnswerUnderTheirOwnIdentifier() {
        Plugin other = FakeServer.newPlugin("ExyliaSandBox");
        Placeholders.register(other, "total_players", request -> 0);
        // Registered last, so ExyliaFFA holds the bare-name slot. That decides
        // what "%total_players%" alone means; it decides nothing here.
        Placeholders.register(plugin, "total_players", request -> 42);

        assertEquals("42", new PapiExpansion(plugin).onRequest(null, "total_players"));
        assertEquals("0", new PapiExpansion(other).onRequest(null, "total_players"));
    }

    /** The same, with the plugins enabling the other way around. */
    @Test
    void theOrderPluginsEnableInDoesNotDecideWhoAnswers() {
        Plugin other = FakeServer.newPlugin("ExyliaSandBox");
        Placeholders.register(plugin, "total_players", request -> 42);
        Placeholders.register(other, "total_players", request -> 0);

        assertEquals("42", new PapiExpansion(plugin).onRequest(null, "total_players"));
        assertEquals("0", new PapiExpansion(other).onRequest(null, "total_players"));
    }

    /** A shared name is still this plugin's, so PlaceholderAPI lists it. */
    @Test
    void listsASharedNameUnderBothPlugins() {
        Plugin other = FakeServer.newPlugin("ExyliaSandBox");
        Placeholders.register(other, "total_players", request -> 0);
        Placeholders.register(plugin, "total_players", request -> 42);

        assertEquals(List.of("total_players"), new PapiExpansion(plugin).getPlaceholders());
        assertEquals(List.of("total_players"), new PapiExpansion(other).getPlaceholders());
    }

    /**
     * Disabling the plugin that held the bare name gives it back to the plugin
     * that still registers it, rather than to nobody: the other plugin never
     * withdrew it, it was only hidden while both were on.
     */
    @Test
    void aSharedNameGoesBackWhenItsHolderIsDisabled() {
        Plugin other = FakeServer.newPlugin("ExyliaSandBox");
        Placeholders.register(other, "total_players", request -> 0);
        Placeholders.register(plugin, "total_players", request -> 42);

        Placeholders.unregisterAll(plugin.getName());

        assertNull(new PapiExpansion(plugin).onRequest(null, "total_players"));
        assertEquals("0", new PapiExpansion(other).onRequest(null, "total_players"));
        assertEquals("0", Placeholders.apply("%total_players%"));
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

    /**
     * Both plugins keep answering, so the warning is no longer about one of
     * them going quiet — it is about what the bare {@code %total_players%}
     * means, which can only be one of them.
     */
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
