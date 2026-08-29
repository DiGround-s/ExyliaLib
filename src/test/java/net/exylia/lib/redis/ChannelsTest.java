package net.exylia.lib.redis;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.SqlSettings;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Channels against a real in-memory Redis: the key format, the framing and
 * the once-only local delivery are exercised for real, only the wire is not.
 */
class ChannelsTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private Plugin plugin;
    private MemoryClient.Network redis;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Staff");
        DatabaseRuntime.installForTests(plugin,
                Map.of("*", SqlSettings.memory("h2", "channels" + DATABASE.incrementAndGet())));
        redis = MemoryClient.network();
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
        RedisRuntime.installForTests((settings, name) -> new MemoryClient(redis));
        DatabaseRuntime.installRedisForTests(settings(serverId));
    }

    private static RedisSettings settings(String serverId) {
        return new RedisSettings(true, "localhost", 6379, "", 0, 8,
                1800, 300, 10_000, "exylia", serverId);
    }

    /** A second server: its own client on the shared network, its own name. */
    private PluginChannels server(String serverId) {
        return new PluginChannels(plugin, "exylia", serverId, new MemoryClient(redis));
    }

    @Test
    @DisplayName("\"a message reaches the subscriber once, marked as local\"")
    void deliversOnceLocally() {
        withRedis("lobby-1");
        Channel alerts = Channels.of(plugin).channel("alerts");
        List<Message> seen = new ArrayList<>();
        alerts.subscribe(seen::add);

        alerts.publish("hello");

        assertTrue(alerts.isNetworked());
        assertEquals(List.of(new Message("lobby-1", "hello", true)), seen);
        assertEquals("lobby-1", Redis.serverId(plugin));
    }

    @Test
    @DisplayName("\"a payload with pipes survives the framing\"")
    void pipesInPayload() {
        withRedis("lobby-1");
        Channel channel = Channels.of(plugin).channel("raw");
        List<Message> seen = new ArrayList<>();
        channel.subscribe(seen::add);

        channel.publish("a|b||c");

        assertEquals("a|b||c", seen.get(0).payload());
    }

    @Test
    @DisplayName("\"two servers on one Redis hear each other, and know who spoke\"")
    void crossServer() {
        PluginChannels lobby = server("lobby-1");
        PluginChannels arena = server("arena-1");
        List<Message> onArena = new ArrayList<>();
        List<Message> onLobby = new ArrayList<>();
        arena.channel("alerts").subscribe(onArena::add);
        lobby.channel("alerts").subscribe(onLobby::add);

        lobby.channel("alerts").publish("report");

        assertEquals(List.of(new Message("lobby-1", "report", false)), onArena);
        assertEquals(List.of(new Message("lobby-1", "report", true)), onLobby);
        lobby.close();
        arena.close();
    }

    @Test
    @DisplayName("\"channels are scoped by plugin and name\"")
    void keyScoping() {
        PluginChannels lobby = server("lobby-1");
        List<Message> seen = new ArrayList<>();
        lobby.channel("other").subscribe(seen::add);

        lobby.channel("alerts").publish("x");

        assertTrue(seen.isEmpty());
        assertSame(lobby.channel("alerts"), lobby.channel("alerts"));
        lobby.close();
    }

    @Test
    @DisplayName("\"closing stops delivery\"")
    void closeStopsDelivery() {
        withRedis("lobby-1");
        Channel alerts = Channels.of(plugin).channel("alerts");
        List<Message> seen = new ArrayList<>();
        Channel.Subscription one = alerts.subscribe(seen::add);
        alerts.subscribe(seen::add);

        one.close();
        alerts.publish("first");
        assertEquals(1, seen.size(), "the closed subscription must not hear it");

        Channels.release(plugin);
        alerts.publish("second");
        assertEquals(1, seen.size(), "a released plugin hears nothing");
    }

    @Test
    @DisplayName("\"without Redis the channel still delivers within this server\"")
    void localBusWhenDisabled() {
        Channel alerts = Channels.of(plugin).channel("alerts");
        List<Message> seen = new ArrayList<>();
        alerts.subscribe(seen::add);

        alerts.publish("hello");

        assertFalse(alerts.isNetworked());
        assertEquals(List.of(new Message("server-1", "hello", true)), seen);
        assertEquals("server-1", Redis.serverId(plugin));
    }

    @Test
    @DisplayName("\"an unreachable Redis still delivers locally, once\"")
    void unreachableFallsBackLocally() {
        MemoryClient broken = new MemoryClient(redis);
        PluginChannels lobby = new PluginChannels(plugin, "exylia", "lobby-1", broken);
        List<Message> seen = new ArrayList<>();
        lobby.channel("alerts").subscribe(seen::add);
        broken.failing(true);

        lobby.channel("alerts").publish("hello");

        assertEquals(List.of(new Message("lobby-1", "hello", true)), seen);
        lobby.close();
    }
}
