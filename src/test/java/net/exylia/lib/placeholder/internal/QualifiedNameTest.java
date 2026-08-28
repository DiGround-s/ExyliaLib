package net.exylia.lib.placeholder.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A name written with the plugin in front resolves inside Exylia text, without
 * PlaceholderAPI and without a viewer.
 *
 * <p>The registry is flat, so two plugins registering {@code total_players}
 * leave one of them unreachable from a config file. This is the way back: the
 * plugin that lost the bare name writes its own spelling in its own files.
 */
class QualifiedNameTest {

    private Plugin ffa;
    private Plugin sandbox;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Registry.clear();
        ffa = FakeServer.newPlugin("ExyliaFFA");
        sandbox = FakeServer.newPlugin("ExyliaSandBox");
    }

    @AfterEach
    void tearDown() {
        Registry.clear();
    }

    @Test
    void bothPluginsAnswerWhenTheTextNamesTheOwner() {
        Placeholders.register(sandbox, "total_players", request -> 7);
        // Registered last, so ExyliaFFA holds the bare name.
        Placeholders.register(ffa, "total_players", request -> 42);

        assertEquals("42", Placeholders.apply("%total_players%"));
        assertEquals("42", Placeholders.apply("%exyliaffa_total_players%"));
        assertEquals("7", Placeholders.apply("%exyliasandbox_total_players%"));
    }

    @Test
    void argumentsSplitOffAQualifiedNameToo() {
        Placeholders.group(ffa, "stats").add("top", request -> request.arg(1, "?")).register();

        assertEquals("1", Placeholders.apply("%exyliaffa_stats_top_kills_1%"));
    }

    @Test
    void aRealRegistrationBeatsASpellingThatMerelyLooksQualified() {
        Placeholders.register(sandbox, "total_players", request -> 7);
        Placeholders.register(ffa, "exyliasandbox_total_players", request -> 99);

        assertEquals("99", Placeholders.apply("%exyliasandbox_total_players%"));
    }

    @Test
    void theOwnerGoesAwayWithItsPlugin() {
        Placeholders.register(sandbox, "total_players", request -> 7);
        assertTrue(Placeholders.has("exyliasandbox_total_players"));

        Placeholders.unregisterAll("ExyliaSandBox");

        assertFalse(Placeholders.has("exyliasandbox_total_players"));
        assertEquals("%exyliasandbox_total_players%",
                Placeholders.apply("%exyliasandbox_total_players%"));
    }
}
