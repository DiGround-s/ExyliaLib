package net.exylia.lib.placeholder.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
