package net.exylia.lib.redis.internal;

import net.exylia.lib.database.internal.Storage;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.RedisSettings;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the connection every plugin's cache shares.
 *
 * <p>One client per distinct Redis, exactly as the database module keeps one
 * datasource per distinct target: two plugins pointed at the same Redis share
 * a connection pool and a subscriber thread. ExyliaCommons could not do this —
 * every plugin shaded its own copy — so a server running four of them held four
 * pools, four subscriber threads and four identities on one channel.
 *
 * <h2>Threads</h2>
 * Safe from any thread. The maps are guarded by one lock, held only while
 * looking a client up or closing it, never across a network call.
 */
public final class RedisRuntime {

    private static final Object LOCK = new Object();

    /** One cache per distinct Redis, keyed by everything that identifies one. */
    private static final Map<String, RowCache> CACHES = new LinkedHashMap<>();
    private static final Map<String, RedisClient> CLIENTS = new LinkedHashMap<>();

    /** Set by tests so a cache can be exercised without a Redis server. */
    private static volatile ClientFactory factory = JedisClient::open;

    private RedisRuntime() {
        throw new AssertionError("No instances.");
    }

    /**
     * The cache for a plugin's settings, opening the connection the first time.
     *
     * <p>Returns {@code null} when Redis is off, unreachable, or its library is
     * not installed. That is not an error: it means every read goes to the
     * database, which is what a server without Redis does anyway.
     *
     * @param plugin   the plugin asking, for the console line and the client name
     * @param settings where to connect and how long to cache
     * @return the shared cache, or {@code null} to run without one
     */
    public static @Nullable RowCache cache(@NotNull Plugin plugin, @NotNull RedisSettings settings) {
        if (!settings.enabled()) {
            return null;
        }
        String key = keyOf(settings);
        Debug debug = Debug.of(plugin);
        synchronized (LOCK) {
            RowCache existing = CACHES.get(key);
            if (existing != null) {
                return existing;
            }
            RedisClient client = openLocked(plugin, settings, key, debug);
            if (client == null) {
                return null;
            }
            RowCache cache = new RowCache(client, settings, settings.serverId(), debug::warn);
            CACHES.put(key, cache);
            debug.log("Redis cache connected to " + settings.host() + ':' + settings.port()
                    + " as \"" + settings.serverId() + "\".");
            return cache;
        }
    }

    /**
     * The shared connection for these settings, opening it the first time.
     *
     * <p>For the modules that need Redis for something other than caching rows
     * — the teleport module's cross-server handover is the first — and it hands
     * back the <em>same</em> client the cache uses rather than opening a second
     * pool: two pools against one Redis is twice the connections and twice the
     * subscriber threads for one server, which is the arrangement ExyliaCommons
     * had and this module exists to stop.
     *
     * <p>Returns {@code null} when Redis is off, unreachable, or its library is
     * not installed, which is never an error: the caller does without.
     *
     * @param plugin   the plugin asking, for the console line and the client name
     * @param settings where to connect
     * @return the shared client, or {@code null} to run without one
     */
    @ApiStatus.Internal
    public static @Nullable RedisClient client(@NotNull Plugin plugin,
                                               @NotNull RedisSettings settings) {
        if (!settings.enabled()) {
            return null;
        }
        synchronized (LOCK) {
            return openLocked(plugin, settings, keyOf(settings), Debug.of(plugin));
        }
    }

    /** The one place a client is opened. Callers hold {@link #LOCK}. */
    private static @Nullable RedisClient openLocked(Plugin plugin, RedisSettings settings,
                                                    String key, Debug debug) {
        RedisClient existing = CLIENTS.get(key);
        if (existing != null) {
            return existing;
        }
        try {
            RedisClient opened = factory.open(settings, "exylia-" + plugin.getName());
            CLIENTS.put(key, opened);
            return opened;
        } catch (Throwable unreachable) {
            // Never fatal. A plugin whose Redis is down must still enable,
            // and it will: the database is the truth and it is still there.
            debug.warn("Redis is configured but could not be reached at " + settings.host()
                    + ':' + settings.port() + " (" + unreachable.getMessage() + ")."
                    + " Continuing without a shared cache: everything works, reads just go"
                    + " to the database. Cross-server changes will not be visible until"
                    + " this is fixed.");
            return null;
        }
    }

    /**
     * Wraps a storage with a cache when there is one to wrap it with.
     *
     * @param storage  the real storage
     * @param cache    the shared cache, or {@code null}
     * @return the storage a repository should use
     */
    public static @NotNull Storage wrap(@NotNull Storage storage, @Nullable RowCache cache) {
        return cache == null ? storage : new CachedStorage(storage, cache);
    }

    /** Closes every connection. Called by ExyliaLib on shutdown. */
    public static void shutdown() {
        synchronized (LOCK) {
            CACHES.values().forEach(RowCache::close);
            CACHES.clear();
            CLIENTS.values().forEach(client -> {
                try {
                    client.close();
                } catch (Throwable ignored) {
                    // Shutting down: a pool that will not close cleanly is not
                    // worth stopping the rest of the shutdown for.
                }
            });
            CLIENTS.clear();
        }
    }

    /** Whether any cache is connected, for diagnostics. */
    public static boolean isActive() {
        synchronized (LOCK) {
            return !CACHES.isEmpty();
        }
    }

    /** Hit rates of every open cache, for the library's own command. */
    public static @NotNull String stats() {
        synchronized (LOCK) {
            if (CACHES.isEmpty()) {
                return "no Redis cache is running";
            }
            StringBuilder summary = new StringBuilder();
            for (RowCache cache : CACHES.values()) {
                if (!summary.isEmpty()) {
                    summary.append("; ");
                }
                summary.append(cache.stats());
            }
            return summary.toString();
        }
    }

    /**
     * Everything that makes two settings the same Redis.
     *
     * <p>The key prefix is part of it: two networks sharing one server are
     * separate keyspaces and must not share a subscriber, or each would act on
     * the other's invalidations.
     */
    private static String keyOf(RedisSettings settings) {
        return settings.host() + ':' + settings.port() + '/' + settings.database()
                + '/' + settings.keyPrefix();
    }

    /** Test seam: supplies a client without a Redis server. */
    public static void installForTests(@Nullable ClientFactory testFactory) {
        synchronized (LOCK) {
            shutdown();
            factory = testFactory == null ? JedisClient::open : testFactory;
        }
    }

    /** How a client is opened, so a test can supply one that needs no server. */
    @FunctionalInterface
    public interface ClientFactory {

        @NotNull RedisClient open(@NotNull RedisSettings settings, @NotNull String name);
    }
}
