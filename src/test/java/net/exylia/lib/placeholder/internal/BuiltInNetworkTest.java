package net.exylia.lib.placeholder.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.proxy.NetworkServer;
import net.exylia.lib.proxy.internal.ProxyRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The network placeholders: players per server and in total, and each server's state. */
class BuiltInNetworkTest {

    @AfterEach
    void tearDown() {
        ProxyRuntime.setServers(List.of());
        FakeServer.online();
        Registry.clear();
    }

    @Test
    void readsTheProxysCachedServerList() {
        FakeServer.install();
        FakeServer.reset();
        Registry.clear();
        BuiltIn.register(FakeServer.newPlugin("ExyliaLib"));
        FakeServer.online(new FakePlayer("Ann").player());

        // Off a network: this server's own count, and every server unknown.
        assertEquals("1", Placeholders.apply("%network_online%"));
        assertEquals("0", Placeholders.apply("%network_players_lobby%"));
        assertEquals("{muted}Unknown", Placeholders.apply("%network_state_lobby%"));

        ProxyRuntime.setServers(List.of(
                new NetworkServer("lobby", 12, true),
                new NetworkServer("bed_wars", 30, true),
                new NetworkServer("Survival", 0, false)));

        assertEquals("42", Placeholders.apply("%network_online%"));
        assertEquals("42", Placeholders.apply("%network_players%"));
        assertEquals("12", Placeholders.apply("%network_players_lobby%"));
        assertEquals("30", Placeholders.apply("%network_players_bed_wars%"));
        assertEquals("0", Placeholders.apply("%network_players_survival%"));
        assertEquals("{success}Online", Placeholders.apply("%network_state_bed_wars%"));
        assertEquals("{error}Offline", Placeholders.apply("%network_state_survival%"));
        assertEquals("{muted}Unknown", Placeholders.apply("%network_state_skyblock%"));
    }
}
