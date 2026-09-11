package net.exylia.lib.redis.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.Storage;
import net.exylia.lib.redis.RedisSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which thread the cache talks to Redis on.
 *
 * <p>The thread asking for a row is very often the server thread, and a Redis
 * round trip there holds the tick for as long as the network takes. So what is
 * tested here is threads, not values: memory may answer on the spot, Redis may
 * not.
 */
class CachedStorageTest {

    @Table("effects")
    record Effect(@Id UUID uuid, @Column("kill_effect") String killEffect, @Column int level) {
    }

    private static final EntityModel<Effect> MODEL = EntityModel.of(Effect.class);
    private static final String BACKGROUND = "database-background";

    private final ExecutorService background =
            Executors.newSingleThreadExecutor(task -> new Thread(task, BACKGROUND));

    @AfterEach
    void stop() {
        background.shutdownNow();
    }

    @Test
    @DisplayName("\"a find that misses memory never asks Redis on the calling thread\"")
    void aMissAsksRedisInTheBackground() throws Exception {
        WatchedClient client = new WatchedClient();
        CachedStorage storage = new CachedStorage(finishedDatabase(), cacheOn(client), background);

        assertNull(storage.find(MODEL, UUID.randomUUID()).get(5, TimeUnit.SECONDS));

        assertTrue(client.threads.contains(BACKGROUND), "Redis should have been asked: " + client.threads);
        assertFalse(client.threads.contains(Thread.currentThread().getName()),
                "the calling thread must never wait on Redis: " + client.threads);
    }

    @Test
    @DisplayName("\"a write the database already finished still reaches Redis off the calling thread\"")
    void aFinishedWriteStoresInTheBackground() throws Exception {
        // A database future that is already complete is the case that slipped
        // through: a callback attached to a finished future runs on the thread
        // attaching it, which is the caller's.
        WatchedClient client = new WatchedClient();
        CachedStorage storage = new CachedStorage(finishedDatabase(), cacheOn(client), background);
        UUID player = UUID.randomUUID();

        storage.save(MODEL, new Effect(player, "flame", 1)).get(5, TimeUnit.SECONDS);

        assertTrue(client.threads.contains(BACKGROUND), "the row should have been stored: " + client.threads);
        assertFalse(client.threads.contains(Thread.currentThread().getName()),
                "the calling thread must never wait on Redis: " + client.threads);
    }

    @Test
    @DisplayName("\"a row held in memory is answered on the spot\"")
    void aMemoryHitIsImmediate() throws Exception {
        RowCache cache = cacheOn(new WatchedClient());
        UUID player = UUID.randomUUID();
        cache.put(MODEL, player, new Effect(player, "flame", 7));
        CachedStorage storage = new CachedStorage(finishedDatabase(), cache, task -> {
            throw new AssertionError("a memory hit must not be scheduled");
        });

        CompletableFuture<Effect> found = storage.find(MODEL, player);

        assertTrue(found.isDone(), "a hit in memory needs no other thread");
        assertEquals(7, found.get().level());
    }

    private static RowCache cacheOn(RedisClient client) {
        RedisSettings settings = new RedisSettings(true, "localhost", 6379, "", 0, 8,
                1800, 300, 10_000, "exylia", "lobby-1");
        return new RowCache(client, settings, "lobby-1", warning -> {
        });
    }

    /** A database whose every call has already finished, finding nothing. */
    private static Storage finishedDatabase() {
        return (Storage) Proxy.newProxyInstance(Storage.class.getClassLoader(),
                new Class<?>[]{Storage.class},
                (proxy, method, arguments) -> method.getName().equals("toString")
                        ? "FinishedDatabase"
                        : CompletableFuture.completedFuture(null));
    }

    /** A Redis in memory that notes the thread of every call that crosses the network. */
    private static final class WatchedClient implements RedisClient {

        final Set<String> threads = ConcurrentHashMap.newKeySet();
        private final MemoryClient memory = new MemoryClient();

        private void note() {
            threads.add(Thread.currentThread().getName());
        }

        @Override
        public String get(String key) {
            note();
            return memory.get(key);
        }

        @Override
        public void set(String key, String value, int ttlSeconds) {
            note();
            memory.set(key, value, ttlSeconds);
        }

        @Override
        public void delete(Collection<String> keys) {
            note();
            memory.delete(keys);
        }

        @Override
        public void deleteByPrefix(String prefix) {
            note();
            memory.deleteByPrefix(prefix);
        }

        @Override
        public void publish(String channel, String message) {
            note();
            memory.publish(channel, message);
        }

        @Override
        public Subscription subscribe(String channel, Consumer<String> handler) {
            return memory.subscribe(channel, handler);
        }

        @Override
        public void close() {
            memory.close();
        }
    }
}
