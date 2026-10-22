package net.exylia.lib.network;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.redis.Message;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.redis.internal.MemoryClient;
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Addressing over an in-memory Redis: who receives a frame, and what a send reports. */
class NetworkTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private Plugin plugin;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Duels");
        DatabaseRuntime.installForTests(plugin,
                Map.of("*", SqlSettings.memory("h2", "network" + DATABASE.incrementAndGet())));
    }

    @AfterEach
    void close() {
        Channels.releaseAll();
        RedisRuntime.installForTests(null);
        DatabaseRuntime.installRedisForTests(null);
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private void withRedis(String serverId) {
        MemoryClient.Network redis = MemoryClient.network();
        RedisRuntime.installForTests((settings, name) -> new MemoryClient(redis));
        DatabaseRuntime.installRedisForTests(new RedisSettings(true, "localhost", 6379, "", 0, 8,
                1800, 300, 10_000, "exylia", serverId));
    }

    @Test
    @DisplayName("\"a message to this server arrives with its payload intact; one to another does not\"")
    void addressing() {
        withRedis("arena-1");
        PluginNetwork network = Network.of(plugin);
        List<Message> seen = new ArrayList<>();
        network.on("match", seen::add);

        assertEquals(Delivery.SENT, network.send("ARENA-1", "match", "a|b"));
        assertEquals(Delivery.SENT, network.send("lobby-1", "match", "not for us"));

        assertEquals(List.of(new Message("arena-1", "a|b", true)), seen);
    }

    @Test
    @DisplayName("\"without Redis, this server is still reachable and another one is not\"")
    void withoutRedis() {
        PluginNetwork network = Network.of(plugin);
        List<Message> seen = new ArrayList<>();
        network.on("match", seen::add);

        assertEquals(Delivery.SENT, network.send(network.serverId(), "match", "here"));
        assertEquals(Delivery.NO_NETWORK, network.send("arena-1", "match", "there"));
        assertEquals(1, seen.size());
    }

    @Test
    @DisplayName("\"a player nobody can find, with no bridge, is no network rather than no player\"")
    void playerWithoutBridge() {
        assertEquals(Delivery.NO_NETWORK,
                Network.of(plugin).send(UUID.randomUUID(), "match", "x").join());
    }

    @Test
    @DisplayName("\"an arrival splits the expected players by whether they are here\"")
    void arrival() {
        UUID here = UUID.randomUUID();
        UUID away = UUID.randomUUID();

        Arrival partial = Arrival.of(List.of(here, away), here::equals);
        Arrival full = Arrival.of(List.of(here), here::equals);

        assertEquals(Set.of(here), partial.arrived());
        assertEquals(Set.of(away), partial.missing());
        assertFalse(partial.complete());
        assertTrue(full.complete());
    }
}
