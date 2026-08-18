package net.exylia.lib.redis.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Everything the module asks of Redis, without naming a Redis library.
 *
 * <p>{@link JedisClient} is the only class that mentions Jedis, so a server
 * without it never loads one. It is also what lets the cache and the bus be
 * tested for real: {@link MemoryClient} implements this in a map, so a test
 * exercises the actual key format, the actual publish ordering and the actual
 * failure handling rather than asserting that a mock was called.
 *
 * <h2>Failures are thrown, not swallowed</h2>
 * Every method may throw. Deciding what a failure means belongs to the caller:
 * a read that fails falls through to the database, while a write that fails
 * must not be followed by the invalidation that would tell peers to re-read a
 * value that was never stored. Swallowing here would take that choice away.
 */
public interface RedisClient {

    /**
     * Reads a value.
     *
     * @param key the full key
     * @return the value, or {@code null} when the key is absent or expired
     */
    @Nullable String get(@NotNull String key);

    /**
     * Writes a value with an expiry.
     *
     * <p>Always with a TTL, never without: a cache entry that outlives the
     * process that could invalidate it is a permanent wrong answer.
     *
     * @param key            the full key
     * @param value          the value
     * @param ttlSeconds     how long it may live, always positive
     */
    void set(@NotNull String key, @NotNull String value, int ttlSeconds);

    /**
     * Removes keys.
     *
     * @param keys the full keys, possibly empty
     */
    void delete(@NotNull Collection<String> keys);

    /**
     * Sends a message to every subscriber of a channel, this server included.
     *
     * @param channel the channel
     * @param message the message
     */
    void publish(@NotNull String channel, @NotNull String message);

    /**
     * Listens on a channel until the returned subscription is closed.
     *
     * <p>Blocking in Redis, so implementations run it on their own thread and
     * return once it is established. The handler is called from that thread.
     *
     * @param channel the channel
     * @param handler what to do with each message
     * @return a handle that stops the listener
     */
    @NotNull Subscription subscribe(@NotNull String channel, @NotNull Consumer<String> handler);

    /** Closes the connections this client owns. */
    void close();

    /** A running subscription. */
    interface Subscription {

        /** Stops listening and releases the connection behind it. */
        void close();
    }
}
