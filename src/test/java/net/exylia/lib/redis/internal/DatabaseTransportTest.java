package net.exylia.lib.redis.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.RowChange;
import net.exylia.lib.database.Table;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code cross-server: database}: the same runtime, carried by the database instead of Redis. */
class DatabaseTransportTest {

    @Table("transport_effects")
    record Effect(@Id UUID uuid, @Column int level) {
    }

    private Plugin lobby;
    private SqlSettings shared;
    private SqlBus arena;

    @BeforeEach
    void open() {
        FakeServer.install();
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        lobby = FakeServer.newPlugin("Lobby");
        shared = SqlSettings.memory("h2", "transport" + System.nanoTime());
        DatabaseRuntime.installForTests(lobby, shared);
        DatabaseRuntime.installRedisForTests(settings("lobby-1"));
        DatabaseRuntime.installBusForTests(shared);
    }

    @AfterEach
    void close() {
        if (arena != null) {
            arena.close();
        }
        RedisRuntime.installForTests(null);
        DatabaseRuntime.installRedisForTests(null);
        DatabaseRuntime.installBusForTests(null);
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static RedisSettings settings(String serverId) {
        return new RedisSettings(true, "localhost", 6379, "", 0, 8,
                1800, 300, 10_000, "exylia", serverId);
    }

    @Test
    @DisplayName("\"the shared client is the database bus, never a Redis connection\"")
    void opensTheBus() {
        RedisRuntime.installForTests((settings, name) -> {
            throw new AssertionError("Redis must not be dialled with cross-server: database");
        });
        RedisRuntime.installServerThreadForTests(() -> false);
        assertInstanceOf(DatabaseBus.class, RedisRuntime.client(lobby, settings("lobby-1")));
    }

    @Test
    @DisplayName("\"a change another server makes reaches onRemoteChange, and no row is kept in memory\"")
    void remoteChangesArrive() throws Exception {
        RedisRuntime.installForTests((settings, name) -> {
            throw new AssertionError("Redis must not be dialled with cross-server: database");
        });
        RedisRuntime.installServerThreadForTests(() -> false);
        List<RowChange> heard = new CopyOnWriteArrayList<>();
        Databases.of(lobby).onRemoteChange(Effect.class, heard::add);

        arena = SqlBus.connect(shared, "arena");
        RowCache peer = new RowCache(arena, settings("arena-1"), "arena-1", warning -> { }, true);
        EntityModel<Effect> model = EntityModel.of(Effect.class);
        UUID changed = UUID.randomUUID();

        long deadline = System.nanoTime() + 10_000_000_000L;
        while (heard.isEmpty() && System.nanoTime() < deadline) {
            FakeServer.tick(100);
            peer.put(model, changed, new Effect(changed, 3));
            Thread.sleep(300);
        }
        assertFalse(heard.isEmpty(), "the lobby should hear the arena's write");
        assertEquals(changed, heard.get(0).id());

        RowCache cache = RedisRuntime.cache(lobby, settings("lobby-1"));
        assertTrue(cache.noticesOnly());
        cache.put(model, changed, new Effect(changed, 4));
        assertNull(cache.local(model, changed), "nothing is remembered over the database bus");
        assertNull(arena.get(CacheKeys.row("exylia", model, changed.toString())), "and nothing is copied to it");
        peer.close();
    }
}
