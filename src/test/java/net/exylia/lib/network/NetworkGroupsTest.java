package net.exylia.lib.network;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.redis.internal.MemoryClient;
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a group setting resolves to on a server with no group of its own and the id Survival-1. */
class NetworkGroupsTest {

    private Plugin plugin;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Market");
        DatabaseRuntime.installForTests(plugin, Map.of("*", SqlSettings.memory("h2", "groups")));
        RedisRuntime.installForTests((settings, name) -> new MemoryClient(MemoryClient.network()));
        DatabaseRuntime.installRedisForTests(new RedisSettings(true, "localhost", 6379, "", 0, 8,
                1800, 300, 10_000, "exylia", "Survival-1"));
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

    @Test
    void scopes() {
        assertEquals("", NetworkGroups.current());
        assertEquals("", NetworkGroups.scope(plugin, null));
        assertEquals("", NetworkGroups.scope(plugin, "default"));
        assertEquals("", NetworkGroups.scope(plugin, "GLOBAL"));
        assertEquals("survival_1", NetworkGroups.scope(plugin, "server"));
        assertEquals("box_pvp", NetworkGroups.scope(plugin, " Box-PvP "));
        assertEquals("", NetworkGroups.scope(plugin, "not a group!"));
    }

    @Test
    void matches() {
        assertTrue(NetworkGroups.matches(plugin, List.of()));
        assertTrue(NetworkGroups.matches(plugin, List.of("boxpvp", "survival-1")));
        assertFalse(NetworkGroups.matches(plugin, List.of("survival", "")));
    }
}
