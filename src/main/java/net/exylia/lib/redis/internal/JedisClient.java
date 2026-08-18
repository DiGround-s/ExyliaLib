package net.exylia.lib.redis.internal;

import net.exylia.lib.redis.RedisSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * The only class in ExyliaLib that names Jedis.
 *
 * <p>Everything else works against {@link RedisClient}, so a server without the
 * Redis library on its classpath never loads this one — the same arrangement
 * the hologram module uses to stay loadable without PacketEvents, and the
 * database module to stay loadable without a Mongo driver.
 *
 * <h2>Two connection shapes, because Redis has two</h2>
 * Commands come from a pool. A subscription cannot: {@code SUBSCRIBE} takes
 * over its connection for as long as it listens, so it gets one of its own,
 * outside the pool, with no read timeout — a subscriber that is idle for an
 * hour is working correctly, not stuck.
 *
 * <h2>A stalled Redis fails, it does not hang</h2>
 * The pool is told how long it may wait for a free connection. ExyliaCommons
 * left that at the library default, which is forever: a Redis that stopped
 * answering turned into threads parked on a borrow that would never return,
 * which is worse than the outage it was reacting to. Here the wait is bounded,
 * the borrow fails, and the caller falls through to the database — which is the
 * behaviour the whole cache is designed around.
 */
final class JedisClient implements RedisClient {

    /** How long a command may take before it is treated as a failure. */
    private static final int TIMEOUT_MILLIS = 2_000;

    /**
     * How long a caller may wait for a connection from the pool.
     *
     * <p>Short on purpose. Waiting here is time added to a database operation
     * that would have succeeded on its own, so the pool gives up quickly and
     * lets the caller do the thing it was going to do anyway.
     */
    private static final Duration BORROW_WAIT = Duration.ofMillis(200);

    /** How long to wait before re-subscribing after the connection drops. */
    private static final long RECONNECT_DELAY_MILLIS = 2_000;

    private final JedisPool pool;
    private final HostAndPort address;
    private final DefaultJedisClientConfig config;

    private JedisClient(JedisPool pool, HostAndPort address, DefaultJedisClientConfig config) {
        this.pool = pool;
        this.address = address;
        this.config = config;
    }

    /**
     * Opens a pool and checks it can actually talk to Redis.
     *
     * @param settings where and how to connect
     * @param name     a client name Redis shows in {@code CLIENT LIST}
     * @return the client
     * @throws RuntimeException if Redis cannot be reached, so the caller can
     *                          report it and carry on without a cache
     */
    static @NotNull JedisClient open(@NotNull RedisSettings settings, @NotNull String name) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(settings.poolSize());
        poolConfig.setMaxIdle(settings.poolSize());
        poolConfig.setMinIdle(1);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(BORROW_WAIT);

        HostAndPort address = new HostAndPort(settings.host(), settings.port());
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(TIMEOUT_MILLIS)
                .socketTimeoutMillis(TIMEOUT_MILLIS)
                .database(settings.database())
                .clientName(name);
        if (!settings.password().isEmpty()) {
            config.password(settings.password());
        }

        DefaultJedisClientConfig built = config.build();
        JedisPool pool = new JedisPool(poolConfig, address, built);
        try (Jedis probe = pool.getResource()) {
            // Reached now rather than on the first player's first lookup. An
            // operator who mistyped the host should learn about it in the
            // startup log, not from a cache that silently never hits.
            probe.ping();
        } catch (RuntimeException unreachable) {
            pool.close();
            throw unreachable;
        }
        return new JedisClient(pool, address, built);
    }

    @Override
    public @Nullable String get(@NotNull String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        }
    }

    @Override
    public void set(@NotNull String key, @NotNull String value, int ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, value, SetParams.setParams().ex(ttlSeconds));
        }
    }

    @Override
    public void delete(@NotNull Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.del(keys.toArray(new String[0]));
        }
    }

    @Override
    public void publish(@NotNull String channel, @NotNull String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    @Override
    public @NotNull Subscription subscribe(@NotNull String channel, @NotNull Consumer<String> handler) {
        return new Listener(channel, handler);
    }

    @Override
    public void close() {
        pool.close();
    }

    /**
     * A subscription on its own thread, which re-subscribes when it drops.
     *
     * <p>Redis pub/sub has no replay, so a message sent while the connection
     * was down is gone. That is why a row's local copy expires on its own and
     * why {@link CachedStorage} reads through to Redis rather than trusting
     * memory indefinitely: a missed message costs one stale local copy for the
     * rest of its expiry, not a permanently wrong server.
     */
    private final class Listener implements Subscription {

        private final Thread thread;
        private final JedisPubSub subscriber;
        private volatile boolean running = true;
        private volatile Jedis connection;

        private Listener(String channel, Consumer<String> handler) {
            this.subscriber = new JedisPubSub() {
                @Override
                public void onMessage(String from, String message) {
                    try {
                        handler.accept(message);
                    } catch (Throwable failure) {
                        // A handler that throws must not kill the subscription:
                        // one unreadable message would otherwise stop every
                        // later invalidation from ever arriving.
                    }
                }
            };
            this.thread = new Thread(() -> listen(channel), "ExyliaLib-Redis-" + channel);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void listen(String channel) {
            while (running) {
                try (Jedis jedis = new Jedis(address, config)) {
                    connection = jedis;
                    // Blocks here for as long as the connection lives.
                    jedis.subscribe(subscriber, channel);
                } catch (Throwable dropped) {
                    // Reported by the cache's own failure path when a command
                    // fails; a subscriber retrying quietly is normal.
                } finally {
                    connection = null;
                }
                if (!running) {
                    return;
                }
                try {
                    Thread.sleep(RECONNECT_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                subscriber.unsubscribe();
            } catch (Throwable ignored) {
                // Already disconnected; the socket close below is what ends it.
            }
            // An interrupt does not break a thread blocked on a socket read, so
            // the socket itself is closed. Without this a disabled plugin
            // leaves the listener alive until the read times out, and it never
            // does — a subscriber connection has no read timeout by design.
            Jedis current = connection;
            if (current != null) {
                try {
                    current.close();
                } catch (Throwable ignored) {
                    // Closing a broken connection is the outcome either way.
                }
            }
            thread.interrupt();
        }
    }
}
