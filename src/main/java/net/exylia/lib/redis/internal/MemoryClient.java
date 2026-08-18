package net.exylia.lib.redis.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A {@link RedisClient} that keeps everything in a map.
 *
 * <p>Not a mock. It stores the same strings under the same keys, delivers
 * published messages to real subscribers, and expires entries by the same
 * clock, so a test against it exercises the key format, the payload format and
 * the publish ordering for real. What it cannot exercise is the wire — and that
 * is the one part {@link JedisClient} confines to itself.
 *
 * <p>Two of these joined by {@link #network()} behave as two servers sharing a
 * Redis, which is what a cross-server test needs and what no single-process
 * mock can fake.
 *
 * <p>Public rather than package-private: the database module's tests build one
 * to stand in for a network, and they live in their own package.
 */
public final class MemoryClient implements RedisClient {

    /** The shared state two clients on the same network see. */
    public static final class Network {

        private final Map<String, Entry> values = new ConcurrentHashMap<>();
        private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();
        private final AtomicInteger published = new AtomicInteger();
        private volatile long now;

        /** Moves the clock, so expiry can be tested without waiting. */
        public void advanceSeconds(long seconds) {
            now += seconds;
        }

        /** How many messages have been published, for asserting on chatter. */
        public int published() {
            return published.get();
        }

        /** How many keys are stored, unexpired. */
        public int size() {
            values.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
            return values.size();
        }
    }

    private record Entry(String value, long expiresAt) {
    }

    private final Network network;
    private volatile boolean failing;
    private volatile boolean failingWrites;
    private volatile boolean closed;

    /** A client on its own private network. */
    public MemoryClient() {
        this(new Network());
    }

    /** A client on a shared network, so several act as several servers. */
    public MemoryClient(@NotNull Network network) {
        this.network = network;
    }

    /** A fresh network for clients to share. */
    public static @NotNull Network network() {
        return new Network();
    }

    /** The network this client is on, so another can join it. */
    public @NotNull Network shared() {
        return network;
    }

    /**
     * Makes every operation fail, as an unreachable Redis does.
     *
     * @param broken whether operations should throw
     */
    public void failing(boolean broken) {
        this.failing = broken;
    }

    /**
     * Makes only {@link #set} fail, leaving publishing intact.
     *
     * <p>Redis rarely fails all at once: a key rejected for being too large, or
     * a replica that has gone read-only, breaks writes while the connection
     * still carries messages perfectly well. That is the case where announcing
     * a value nobody stored does real damage, and a client where everything
     * fails cannot express it.
     *
     * @param broken whether stores should throw
     */
    public void failingWrites(boolean broken) {
        this.failingWrites = broken;
    }

    @Override
    public @Nullable String get(@NotNull String key) {
        check();
        Entry entry = network.values.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt <= network.now) {
            network.values.remove(key);
            return null;
        }
        return entry.value;
    }

    @Override
    public void set(@NotNull String key, @NotNull String value, int ttlSeconds) {
        check();
        if (failingWrites) {
            throw new IllegalStateException("Redis refused the write");
        }
        network.values.put(key, new Entry(value, network.now + ttlSeconds));
    }

    @Override
    public void delete(@NotNull Collection<String> keys) {
        check();
        keys.forEach(network.values::remove);
    }

    @Override
    public void publish(@NotNull String channel, @NotNull String message) {
        check();
        network.published.incrementAndGet();
        // Delivered inline, on the publishing thread. Real Redis delivers on
        // the subscriber's, but a test that has to wait for another thread is
        // a test that is flaky on a loaded machine. What matters here is that
        // every subscriber sees it, including the sender's own — which is what
        // makes the self-filter worth testing at all.
        for (Consumer<String> handler : network.subscribers.getOrDefault(channel, List.of())) {
            handler.accept(message);
        }
    }

    @Override
    public @NotNull Subscription subscribe(@NotNull String channel, @NotNull Consumer<String> handler) {
        network.subscribers.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> network.subscribers
                .getOrDefault(channel, new CopyOnWriteArrayList<>())
                .remove(handler);
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Whether {@link #close()} was called, so a test can assert on cleanup. */
    public boolean closed() {
        return closed;
    }

    private void check() {
        if (failing) {
            throw new IllegalStateException("Redis is unreachable");
        }
    }
}
