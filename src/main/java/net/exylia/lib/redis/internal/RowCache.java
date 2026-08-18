package net.exylia.lib.redis.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.redis.RedisSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Rows held in memory, backed by Redis, invalidated across the network.
 *
 * <p>Two levels, and they are not the same thing:
 *
 * <ul>
 *   <li><b>L1 is this process's memory.</b> Caffeine, bounded and expiring,
 *       because an unbounded map of every row anyone touched is a leak with
 *       extra steps. A hit costs a hash lookup.</li>
 *   <li><b>L2 is Redis.</b> Shared by every server pointed at it. A hit costs
 *       one round trip and a parse, which is still far less than a query, and
 *       it is the level that makes a server switch work.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread and none of them block on the game
 * thread by accident — they are called from the database module's background
 * executor, inside the future chain of the operation they belong to. The
 * subscriber calls {@link #onMessage} from its own thread; Caffeine is safe
 * against that.
 *
 * <h2>What a failure does</h2>
 * Nothing that throws here reaches a caller. A read that fails is a miss and
 * the database answers; a write that fails skips its own publish, so no peer is
 * ever sent to look at a value that was not stored. Failures are counted and
 * reported once rather than per operation: a Redis that is down produces one
 * line, not one line per player per second.
 */
public final class RowCache {

    private final RedisClient client;
    private final RedisSettings settings;
    private final String serverId;
    private final Consumer<String> warnings;

    /** Rows by full Redis key, so L1 and L2 are addressed identically. */
    private final Cache<String, Object> local;

    /**
     * Tables this server actually reads, by name.
     *
     * <p>An incoming invalidation names a table; without this, a message about
     * another plugin's table would still cost a scan of the local cache. It
     * also gives the message a model to build keys with.
     */
    private final Map<String, EntityModel<?>> tables = new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean reportedFailure;

    private RedisClient.Subscription subscription;

    /**
     * Builds the cache and starts listening for invalidations.
     *
     * @param client   the connection, already open
     * @param settings sizes, expiry and the key prefix
     * @param serverId this server's name on the network, used to ignore its own messages
     * @param warnings where a Redis problem is reported, once
     */
    public RowCache(@NotNull RedisClient client,
                    @NotNull RedisSettings settings,
                    @NotNull String serverId,
                    @NotNull Consumer<String> warnings) {
        this.client = client;
        this.settings = settings;
        this.serverId = serverId;
        this.warnings = warnings;
        this.local = Caffeine.newBuilder()
                .maximumSize(settings.localEntries())
                // After write, not after access: a row read constantly by a
                // scoreboard would otherwise never expire, and expiry is the
                // backstop for the one invalidation that failed to arrive.
                .expireAfterWrite(Duration.ofSeconds(settings.localSeconds()))
                .build();
        this.subscription = client.subscribe(CacheKeys.channel(settings.keyPrefix()), this::onMessage);
    }

    /** Notes that this server reads a table, so invalidations for it matter. */
    void register(@NotNull EntityModel<?> model) {
        tables.putIfAbsent(model.table(), model);
    }

    // ------------------------------------------------------------------ read

    /**
     * A row from memory, then from Redis.
     *
     * @param model the compiled model
     * @param id    the key in record form
     * @param <T>   the record type
     * @return the row, or {@code null} for a miss the caller must resolve
     */
    @SuppressWarnings("unchecked")
    <T> @Nullable T get(@NotNull EntityModel<T> model, @NotNull Object id) {
        String key = keyOf(model, id);
        if (key == null) {
            return null;
        }

        Object cached = local.getIfPresent(key);
        if (cached != null) {
            hits.incrementAndGet();
            return (T) cached;
        }

        try {
            String payload = client.get(key);
            if (payload == null) {
                misses.incrementAndGet();
                return null;
            }
            T row = RowCodec.decode(model, payload);
            if (row == null) {
                // Unreadable rather than absent: drop it so the next lookup is
                // a clean miss instead of paying for the same failed parse.
                client.delete(List.of(key));
                misses.incrementAndGet();
                return null;
            }
            local.put(key, row);
            hits.incrementAndGet();
            return row;
        } catch (Throwable unreachable) {
            report(unreachable);
            misses.incrementAndGet();
            return null;
        }
    }

    /** Whether a row is already known to exist, without asking the database. */
    boolean has(@NotNull EntityModel<?> model, @NotNull Object id) {
        String key = keyOf(model, id);
        return key != null && local.getIfPresent(key) != null;
    }

    // ----------------------------------------------------------------- write

    /**
     * Stores a row and tells the network it changed.
     *
     * <p>The order is the contract: memory, then Redis, then the message. A peer
     * that receives the message and re-reads immediately must find the new
     * value already there, which only holds if the store completed first.
     *
     * @param model the compiled model
     * @param id    the key in record form
     * @param row   the row
     * @param <T>   the record type
     */
    <T> void put(@NotNull EntityModel<T> model, @Nullable Object id, @NotNull T row) {
        if (id == null) {
            return;
        }
        String key = keyOf(model, id);
        if (key == null) {
            return;
        }
        register(model);
        local.put(key, row);
        try {
            client.set(key, RowCodec.encode(model, row), settings.ttlSeconds());
        } catch (Throwable unreachable) {
            // No publish. Peers dropping their copy would send them to Redis
            // for a value that is not there, turning one failed write into a
            // network-wide fallback to the database for the whole TTL.
            report(unreachable);
            return;
        }
        publish(model.table(), storedId(model, id));
    }

    /** Removes a row here and everywhere. */
    void drop(@NotNull EntityModel<?> model, @NotNull Object id) {
        String key = keyOf(model, id);
        if (key == null) {
            return;
        }
        local.invalidate(key);
        try {
            client.delete(List.of(key));
        } catch (Throwable unreachable) {
            report(unreachable);
            return;
        }
        publish(model.table(), storedId(model, id));
    }

    /**
     * Removes every row of a table, here and everywhere.
     *
     * <p>Local memory and the message are enough: a peer drops its own memory
     * and the Redis copies expire on their own TTL. Deliberately no
     * {@code SCAN}: ExyliaCommons scanned the whole keyspace on every save, and
     * on a busy server that is a full keyspace walk several times a second.
     * This path is a wipe, not a routine write.
     */
    void dropTable(@NotNull EntityModel<?> model) {
        dropLocalTable(model.table());
        publish(model.table(), null);
    }

    // ---------------------------------------------------------------- shared

    /**
     * Handles a peer's message.
     *
     * <p>Runs on the subscriber thread. Its own messages are dropped first,
     * because every server sees every message and the sender is the common
     * case on a busy network.
     */
    void onMessage(@NotNull String message) {
        Invalidation invalidation = Invalidation.decode(message);
        if (invalidation == null || invalidation.sentBy(serverId)) {
            return;
        }
        EntityModel<?> model = tables.get(invalidation.table());
        if (model == null) {
            // A table no repository here reads. Another plugin's, or another
            // server's — either way there is nothing local to drop.
            return;
        }
        if (invalidation.wholeTable()) {
            dropLocalTable(invalidation.table());
            return;
        }
        // Only the local copy. The Redis value is the new one the sender just
        // wrote: deleting it would throw away the fresh row and send every
        // server in the network to the database for it.
        local.invalidate(CacheKeys.table(settings.keyPrefix(), invalidation.table()) + invalidation.id());
    }

    private void dropLocalTable(String table) {
        String prefix = CacheKeys.table(settings.keyPrefix(), table);
        List<String> doomed = new ArrayList<>();
        for (String key : local.asMap().keySet()) {
            if (key.startsWith(prefix)) {
                doomed.add(key);
            }
        }
        local.invalidateAll(doomed);
    }

    private void publish(String table, @Nullable String id) {
        try {
            client.publish(CacheKeys.channel(settings.keyPrefix()),
                    new Invalidation(serverId, table, id).encode());
        } catch (Throwable unreachable) {
            // The value is stored; only the notification failed. Peers keep
            // their copy until it expires, which is what the local TTL is for.
            report(unreachable);
        }
    }

    /** The full Redis key for a row, or {@code null} when the id cannot be encoded. */
    private @Nullable String keyOf(EntityModel<?> model, Object id) {
        String stored = storedId(model, id);
        return stored == null ? null : CacheKeys.row(settings.keyPrefix(), model, stored);
    }

    /**
     * The id as the database stores it.
     *
     * <p>Through the column's own encoder, never {@code toString()}. A
     * {@code UUID} key encoded one way here and another way by the column would
     * produce a key that never matches what a write stored — a cache that
     * silently never hits, which looks exactly like a cache that is working.
     */
    private @Nullable String storedId(EntityModel<?> model, Object id) {
        Object encoded = model.id().encode(id);
        return encoded == null ? null : String.valueOf(encoded);
    }

    private void report(Throwable failure) {
        failures.incrementAndGet();
        if (reportedFailure) {
            return;
        }
        reportedFailure = true;
        warnings.accept("Redis is not answering, so rows are being read from the database instead: "
                + failure.getMessage() + ". Nothing is lost and nothing else needs doing;"
                + " this is reported once until the server restarts.");
    }

    /** Stops listening and releases what this cache owns. */
    public void close() {
        RedisClient.Subscription current = subscription;
        subscription = null;
        if (current != null) {
            current.close();
        }
        local.invalidateAll();
        tables.clear();
    }

    /** Hits, misses and failures, for the library's own diagnostics. */
    public @NotNull String stats() {
        long hit = hits.get();
        long miss = misses.get();
        long total = hit + miss;
        long rate = total == 0 ? 0 : hit * 100 / total;
        return "RowCache[" + hit + " hits, " + miss + " misses (" + rate + "%), "
                + failures.get() + " failures, " + local.estimatedSize() + " in memory]";
    }
}
