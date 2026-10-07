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

import static org.junit.jupiter.api.Assertions.assertFalse;

/** A listener registered while Redis is down is attached once it answers, not lost. */
class RemoteChangeRetryTest {

    @Table("remote_effects")
    record Effect(@Id UUID uuid, @Column int level) {
    }

    private Plugin lobby;

    @BeforeEach
    void open() {
        FakeServer.install();
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        lobby = FakeServer.newPlugin("Lobby");
        DatabaseRuntime.installForTests(lobby, SqlSettings.memory("h2", "remote" + System.nanoTime()));
    }

    @AfterEach
    void close() {
        RedisRuntime.installForTests(null);
        DatabaseRuntime.installRedisForTests(null);
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static RedisSettings settings(String serverId) {
        return new RedisSettings(true, "localhost", 6379, "", 0, 8,
                1800, 300, 10_000, "exylia", serverId);
    }

    @Test
    @DisplayName("a remote-change listener registered during an outage hears changes once Redis is back")
    void listenerSurvivesOutage() throws InterruptedException {
        RedisRuntime.installForTests((settings, name) -> {
            throw new IllegalStateException("connection refused");
        });
        DatabaseRuntime.installRedisForTests(settings("lobby-1"));
        List<RowChange> heard = new CopyOnWriteArrayList<>();
        Databases.of(lobby).onRemoteChange(Effect.class, heard::add);
        FakeServer.tick(200);

        MemoryClient.Network network = MemoryClient.network();
        RedisRuntime.installForTests((settings, name) -> new MemoryClient(network));
        RowCache arena = new RowCache(new MemoryClient(network), settings("arena-1"), "arena-1", warning -> {
        });
        EntityModel<Effect> model = EntityModel.of(Effect.class);

        long deadline = System.nanoTime() + 10_000_000_000L;
        while (heard.isEmpty() && System.nanoTime() < deadline) {
            FakeServer.tick(100);
            Thread.sleep(50);
            arena.drop(model, UUID.randomUUID());
            Thread.sleep(50);
        }
        assertFalse(heard.isEmpty(), "the listener should have been attached once Redis answered");
    }
}
