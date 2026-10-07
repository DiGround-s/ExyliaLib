package net.exylia.lib.redis.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where and under what a connection is opened: never the server thread, never under a lock. */
class RedisRuntimeTest {

    private Plugin plugin;

    @BeforeEach
    void open() {
        FakeServer.install();
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Lobby");
    }

    @AfterEach
    void close() {
        RedisRuntime.installForTests(null);
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static RedisSettings settings(int database) {
        return new RedisSettings(true, "localhost", 6379, "", database, 8,
                1800, 300, 10_000, "exylia", "lobby-1");
    }

    @Test
    @DisplayName("the server thread never opens a connection; it is opened in the background")
    void serverThreadNeverOpens() throws InterruptedException {
        Thread server = Thread.currentThread();
        AtomicReference<Thread> openedOn = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        RedisRuntime.installForTests((settings, name) -> {
            openedOn.set(Thread.currentThread());
            opened.countDown();
            return new MemoryClient();
        });
        RedisRuntime.installServerThreadForTests(() -> Thread.currentThread() == server);

        assertNull(RedisRuntime.client(plugin, settings(0)),
                "a connection that is not open yet is not waited for on the server thread");
        assertTrue(opened.await(5, TimeUnit.SECONDS), "the miss should start a background open");
        assertNotSame(server, openedOn.get());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        RedisClient client = null;
        while (client == null && System.nanoTime() < deadline) {
            client = RedisRuntime.client(plugin, settings(0));
            Thread.sleep(10);
        }
        assertNotNull(client, "the next lookup should find the connection the background opened");
    }

    @Test
    @DisplayName("a slow connect blocks neither lookups of open connections nor a second open")
    void slowConnectHoldsNoLock() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        RedisRuntime.installForTests((settings, name) -> {
            opens.incrementAndGet();
            if (settings.database() == 1) {
                connecting.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return new MemoryClient();
        });
        RedisClient fast = RedisRuntime.client(plugin, settings(0));
        assertNotNull(fast);

        CompletableFuture<RedisClient> slow = CompletableFuture.supplyAsync(
                () -> RedisRuntime.client(plugin, settings(1)));
        CompletableFuture<RedisClient> sameSlow = null;
        try {
            assertTrue(connecting.await(5, TimeUnit.SECONDS));
            // Another thread asking for the same Redis joins the attempt in
            // flight instead of dialling it a second time.
            sameSlow = CompletableFuture.supplyAsync(() -> RedisRuntime.client(plugin, settings(1)));

            CompletableFuture<RedisClient> lookup = CompletableFuture.supplyAsync(
                    () -> RedisRuntime.client(plugin, settings(0)));
            assertSame(fast, lookup.get(2, TimeUnit.SECONDS),
                    "an open connection must not wait behind somebody else's connect");
            assertTrue(CompletableFuture.supplyAsync(RedisRuntime::isActive).get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        RedisClient opened = slow.get(5, TimeUnit.SECONDS);
        assertNotNull(opened);
        assertSame(opened, sameSlow.get(5, TimeUnit.SECONDS));
        assertEquals(2, opens.get(), "one connect per Redis");
    }

    @Test
    @DisplayName("the per-operation lookup never waits for a connect, even off the server thread")
    void cacheIfOpenNeverWaits() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> openedOn = new AtomicReference<>();
        RedisRuntime.installForTests((settings, name) -> {
            openedOn.set(Thread.currentThread());
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new MemoryClient();
        });
        try {
            CompletableFuture<RowCache> lookup = CompletableFuture.supplyAsync(
                    () -> RedisRuntime.cacheIfOpen(plugin, settings(0)));
            assertNull(lookup.get(2, TimeUnit.SECONDS), "a connect still under way is not waited for");
        } finally {
            release.countDown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        RowCache cache = null;
        while (cache == null && System.nanoTime() < deadline) {
            cache = RedisRuntime.cacheIfOpen(plugin, settings(0));
            Thread.sleep(10);
        }
        assertNotNull(cache, "the background open should be found by a later lookup");
    }

    @Test
    @DisplayName("a connection or cache finished after shutdown is closed, not published")
    void nothingOutlivesShutdown() {
        AtomicReference<MemoryClient> made = new AtomicReference<>();
        RedisRuntime.installForTests((settings, name) -> {
            MemoryClient client = new MemoryClient();
            made.set(client);
            // The library goes down while this connect is on the wire.
            RedisRuntime.shutdown();
            return client;
        });
        assertNull(RedisRuntime.client(plugin, settings(0)));
        assertTrue(made.get().closed(), "an orphaned pool must be closed");
        assertTrue(!RedisRuntime.isActive(), "nothing may be published after shutdown");

        AtomicInteger subscribed = new AtomicInteger();
        RedisRuntime.installForTests((settings, name) -> new ShuttingDownClient(subscribed));
        assertNull(RedisRuntime.cache(plugin, settings(0)));
        assertEquals(1, subscribed.get());
        assertTrue(!RedisRuntime.isActive(), "a cache built across a shutdown must not be kept");
    }

    /** Shuts the library down from inside the cache's constructor, as a racing disable would. */
    private static final class ShuttingDownClient implements RedisClient {

        private final MemoryClient memory = new MemoryClient();
        private final AtomicInteger subscribed;

        ShuttingDownClient(AtomicInteger subscribed) {
            this.subscribed = subscribed;
        }

        @Override
        public String get(String key) {
            return memory.get(key);
        }

        @Override
        public void set(String key, String value, int ttlSeconds) {
            memory.set(key, value, ttlSeconds);
        }

        @Override
        public void delete(java.util.Collection<String> keys) {
            memory.delete(keys);
        }

        @Override
        public void deleteByPrefix(String prefix) {
            memory.deleteByPrefix(prefix);
        }

        @Override
        public void publish(String channel, String message) {
            memory.publish(channel, message);
        }

        @Override
        public Subscription subscribe(String channel, java.util.function.Consumer<String> handler) {
            subscribed.incrementAndGet();
            RedisRuntime.shutdown();
            return memory.subscribe(channel, handler);
        }

        @Override
        public void close() {
            memory.close();
        }
    }
}
