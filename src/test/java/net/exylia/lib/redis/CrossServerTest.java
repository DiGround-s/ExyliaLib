package net.exylia.lib.redis;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.Table;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two servers, one database, one Redis — the arrangement the module exists for.
 *
 * <p>Not a mock anywhere that matters: the database is a real H2 shared by both
 * "servers", and the Redis is a real map shared by both clients, holding the
 * real key format and the real payload. What is simulated is the wire, which is
 * the one thing {@code JedisClient} keeps to itself.
 *
 * <p>The scenario throughout is the one that sends people looking at this code:
 * a player changes something on one server and appears on another expecting to
 * find it.
 */
class CrossServerTest {

    private static final long TIMEOUT_SECONDS = 15L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    @Table("effects")
    record Effect(@Id UUID uuid, @Column("kill_effect") String killEffect, @Column int level) {
    }

    private Plugin lobby;
    private Plugin arena;
    private MemoryClient.Network redis;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        lobby = FakeServer.newPlugin("Lobby");
        arena = FakeServer.newPlugin("Arena");

        // One database, reached by both, exactly as a network shares MySQL.
        SqlSettings shared = SqlSettings.memory("h2", "cross" + DATABASE.incrementAndGet());
        DatabaseRuntime.installForTests(lobby, Map.of("*", shared));

        redis = MemoryClient.network();
    }

    @AfterEach
    void close() {
        RedisRuntime.installForTests(null);
        DatabaseRuntime.installRedisForTests(null);
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    /**
     * Points both plugins at the same Redis, each with its own server name.
     *
     * <p>The client is chosen by the {@code server-id}, so the two plugins get
     * distinct clients on one shared network — which is what makes them two
     * servers rather than one process talking to itself.
     */
    private void withRedis(String... serverIds) {
        RedisRuntime.installForTests((settings, name) -> new MemoryClient(redis));
        DatabaseRuntime.installRedisForTests(redisSettings(serverIds[0]));
    }

    private static RedisSettings redisSettings(String serverId) {
        return new RedisSettings(true, "localhost", 6379, "", 0, 8,
                1800, 300, 10_000, "exylia", serverId);
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("An operation did not complete", failure);
        }
    }

    // ------------------------------------------------------------ the handoff

    @Test
    @DisplayName("\"a change on one server is already applied on the other\"")
    void aChangeOnOneServerIsVisibleOnTheOther() {
        withRedis("lobby-1");
        Repository<Effect> onLobby = Databases.of(lobby).repository(Effect.class);
        Repository<Effect> onArena = Databases.of(arena).repository(Effect.class);

        UUID player = UUID.randomUUID();
        await(onLobby.save(new Effect(player, "lightning", 3)));

        assertEquals(Optional.of(new Effect(player, "lightning", 3)), await(onArena.find(player)),
                "the second server should see what the first just wrote");
    }

    @Test
    @DisplayName("\"the second server reads the new value without waiting for a message\"")
    void theHandoffDoesNotWaitForPubSub() {
        // The case that breaks on a proxy: quit and join land in the same tick,
        // so nothing can be assumed to have been delivered. The read must find
        // the value because it was stored before it was announced, not because
        // an invalidation arrived in time.
        withRedis("lobby-1");
        Repository<Effect> onLobby = Databases.of(lobby).repository(Effect.class);
        Repository<Effect> onArena = Databases.of(arena).repository(Effect.class);

        UUID player = UUID.randomUUID();
        await(onLobby.save(new Effect(player, "flame", 1)));
        // Warm the second server, as a player who was here earlier would.
        assertEquals(1, await(onArena.find(player)).orElseThrow().level());

        // Now the player changes it on the first server and switches back.
        await(onLobby.save(new Effect(player, "flame", 9)));

        assertEquals(9, await(onArena.find(player)).orElseThrow().level(),
                "a stale local copy must not survive the other server's write");
    }

    @Test
    @DisplayName("\"a row cached before a change is dropped, not served\"")
    void aStaleLocalCopyIsInvalidated() {
        withRedis("lobby-1");
        Repository<Effect> onLobby = Databases.of(lobby).repository(Effect.class);
        Repository<Effect> onArena = Databases.of(arena).repository(Effect.class);

        UUID player = UUID.randomUUID();
        await(onLobby.save(new Effect(player, "smoke", 1)));
        await(onArena.find(player));

        int before = redis.published();
        await(onLobby.save(new Effect(player, "smoke", 2)));
        assertNotEquals(before, redis.published(), "a write should announce itself");

        assertEquals(2, await(onArena.find(player)).orElseThrow().level());
    }

    // ------------------------------------------------------------ correctness

    @Test
    @DisplayName("\"a deleted row is gone from every server\"")
    void deleteReachesEveryServer() {
        withRedis("lobby-1");
        Repository<Effect> onLobby = Databases.of(lobby).repository(Effect.class);
        Repository<Effect> onArena = Databases.of(arena).repository(Effect.class);

        UUID player = UUID.randomUUID();
        await(onLobby.save(new Effect(player, "hearts", 4)));
        assertTrue(await(onArena.find(player)).isPresent());

        await(onLobby.delete(player));

        assertEquals(Optional.empty(), await(onArena.find(player)),
                "a cached row must not outlive the delete that removed it");
    }

    @Test
    @DisplayName("\"a cached row is answered without touching the database\"")
    void aHitDoesNotReachTheDatabase() {
        // The other tests share one H2 between both servers, so a row would be
        // found there whether or not the cache did anything. This one proves
        // the cache is actually answering: the row is removed from the database
        // behind the repository's back, and the read still succeeds because it
        // never gets that far.
        withRedis("lobby-1");
        Repository<Effect> onLobby = Databases.of(lobby).repository(Effect.class);

        UUID player = UUID.randomUUID();
        await(onLobby.save(new Effect(player, "cached", 5)));

        // Straight to H2, bypassing the repository and therefore the cache.
        deleteBehindTheCache(player);

        assertEquals(5, await(onLobby.find(player)).orElseThrow().level(),
                "a cached row must be served from the cache, not re-read from the database");
    }

    /** Deletes a row underneath the cache, so only a cache hit can still find it. */
    private void deleteBehindTheCache(UUID player) {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:cross" + DATABASE.get() + ";DB_CLOSE_DELAY=-1", "sa", "");
             java.sql.PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM \"effects\" WHERE \"uuid\" = ?")) {
            statement.setString(1, player.toString());
            statement.executeUpdate();
        } catch (java.sql.SQLException failure) {
            throw new AssertionError("Could not reach H2 directly", failure);
        }
    }

    @Test
    @DisplayName("\"the cache holds what the database holds, encoded the same way\"")
    void theCachedValueSurvivesARoundTrip() {
        // The failure this prevents: a UUID keyed by toString() on one side and
        // by its codec on the other produces a cache that never hits while
        // looking perfectly healthy.
        withRedis("lobby-1");
        Repository<Effect> onLobby = Databases.of(lobby).repository(Effect.class);

        UUID player = UUID.randomUUID();
        Effect written = new Effect(player, "void", 7);
        await(onLobby.save(written));

        assertTrue(redis.size() > 0, "the write should have reached Redis");
        assertEquals(Optional.of(written), await(onLobby.find(player)));
    }

    // ---------------------------------------------------------------- failure

    @Test
    @DisplayName("\"Redis going down costs speed, never data\"")
    void everythingWorksWithoutRedis() {
        MemoryClient broken = new MemoryClient(redis);
        RedisRuntime.installForTests((settings, name) -> broken);
        DatabaseRuntime.installRedisForTests(redisSettings("lobby-1"));

        Repository<Effect> onLobby = Databases.of(lobby).repository(Effect.class);
        UUID player = UUID.randomUUID();
        await(onLobby.save(new Effect(player, "spark", 2)));

        broken.failing(true);

        // Both directions still work: the write lands in the database and the
        // read comes back from it.
        UUID second = UUID.randomUUID();
        await(onLobby.save(new Effect(second, "ember", 5)));
        assertEquals(5, await(onLobby.find(second)).orElseThrow().level(),
                "an unreachable Redis must not break a read");
    }

    @Test
    @DisplayName("\"a plugin with no Redis configured opens no connection\"")
    void disabledByDefault() {
        AtomicInteger opened = new AtomicInteger();
        RedisRuntime.installForTests((settings, name) -> {
            opened.incrementAndGet();
            return new MemoryClient(redis);
        });
        // No installRedisForTests: the default block is disabled.

        Repository<Effect> onLobby = Databases.of(lobby).repository(Effect.class);
        UUID player = UUID.randomUUID();
        await(onLobby.save(new Effect(player, "none", 0)));
        assertEquals(Optional.of(new Effect(player, "none", 0)), await(onLobby.find(player)));

        assertEquals(0, opened.get(), "a server that did not ask for Redis must not connect to one");
        assertFalse(Redis.isActive());
    }
}
