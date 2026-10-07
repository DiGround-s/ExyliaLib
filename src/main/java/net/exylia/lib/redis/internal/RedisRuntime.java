package net.exylia.lib.redis.internal;

import net.exylia.lib.database.internal.Storage;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
 * Safe from any thread. No lock is held across a network call, and a lookup
 * that finds its connection takes no lock at all. The server thread never
 * opens a connection: it starts one in the background and runs without Redis
 * until it is there.
 */
public final class RedisRuntime {

    // Concurrent maps rather than one lock around plain ones: a lookup that
    // finds its connection is the common case, it runs on the server thread
    // from every publish, join and heartbeat, and it must never queue behind
    // another thread that is inside a four-second connect.
    /** One cache per distinct Redis, keyed by everything that identifies one. */
    private static final Map<String, RowCache> CACHES = new ConcurrentHashMap<>();
    private static final Map<String, RedisClient> CLIENTS = new ConcurrentHashMap<>();

    /** When a connection that failed may be attempted again, by key. */
    private static final Map<String, Long> RETRIES = new ConcurrentHashMap<>();

    /** The attempt under way for each key, so concurrent askers share one connect. */
    private static final Map<String, CompletableFuture<RedisClient>> OPENING = new ConcurrentHashMap<>();

    /** How long a failed connection is left alone before it is tried again. */
    private static final long RETRY_AFTER_MILLIS = 30_000L;

    /**
     * Bumped by {@link #shutdown}, so an open that was already on the wire when
     * the library went down closes what it got instead of publishing it into
     * maps nobody will ever close again.
     */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    /** Set by tests so a cache can be exercised without a Redis server. */
    private static volatile ClientFactory factory = JedisClient::open;

    /** Whether the caller is a thread that must not wait on the network; a test seam. */
    private static volatile BooleanSupplier onServerThread = RedisRuntime::isServerThread;

    private RedisRuntime() {
        throw new AssertionError("No instances.");
    }

    /**
     * The cache for a plugin's settings, opening the connection the first time.
     *
     * <p>Returns {@code null} when Redis is off, unreachable, or its library is
     * not installed. That is not an error: it means every read goes to the
     * database, which is what a server without Redis does anyway. Asked from
     * the server thread before the connection exists, it also answers
     * {@code null} and opens the connection in the background, so asking
     * again a moment later finds it.
     *
     * @param plugin   the plugin asking, for the console line and the client name
     * @param settings where to connect and how long to cache
     * @return the shared cache, or {@code null} to run without one
     */
    public static @Nullable RowCache cache(@NotNull Plugin plugin, @NotNull RedisSettings settings) {
        return cache(plugin, settings, !onServerThread.getAsBoolean());
    }

    /**
     * The cache if its connection is already open, never waiting for one.
     *
     * <p>For paths that run on every database operation. Waiting there for a
     * connect would, while Redis is down, park every database thread on the
     * same dead host once per retry window — join-time loads included — so a
     * miss starts the open in the background and answers {@code null}: the
     * operation runs uncached and a later one finds the cache.
     *
     * @param plugin   the plugin asking
     * @param settings where to connect and how long to cache
     * @return the shared cache, or {@code null} for now
     */
    public static @Nullable RowCache cacheIfOpen(@NotNull Plugin plugin, @NotNull RedisSettings settings) {
        return cache(plugin, settings, false);
    }

    private static @Nullable RowCache cache(Plugin plugin, RedisSettings settings, boolean mayWait) {
        if (!settings.enabled()) {
            return null;
        }
        String key = keyOf(settings);
        RowCache existing = CACHES.get(key);
        if (existing != null) {
            return existing;
        }
        int generation = GENERATION.get();
        RedisClient client = client(plugin, settings, key, mayWait);
        if (client == null) {
            return null;
        }
        Debug debug = Debug.of(plugin);
        // Built outside any lock: the constructor starts a subscriber. Two
        // threads racing here both build one, and the loser closes its own
        // rather than leave a second subscriber running.
        RowCache built = new RowCache(client, settings, settings.serverId(), debug::warn);
        RowCache won = publish(CACHES, key, built, generation);
        if (won != built) {
            built.close();
            return won;
        }
        debug.log("Redis cache connected to " + settings.host() + ':' + settings.port()
                + " as \"" + settings.serverId() + "\".");
        return built;
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
     * not installed, which is never an error: the caller does without. From
     * the server thread a connection that is not open yet is also
     * {@code null}: opening one is a DNS lookup, a connect and a ping, up to
     * several seconds of a frozen tick, so it is opened in the background and
     * the next caller finds it.
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
        return client(plugin, settings, keyOf(settings), !onServerThread.getAsBoolean());
    }

    /**
     * The shared connection if it is already open, never waiting for one.
     *
     * <p>A miss starts the open in the background, so asking again shortly
     * finds it; see {@link #cacheIfOpen}.
     *
     * @param plugin   the plugin asking
     * @param settings where to connect
     * @return the shared client, or {@code null} for now
     */
    @ApiStatus.Internal
    public static @Nullable RedisClient clientIfOpen(@NotNull Plugin plugin,
                                                     @NotNull RedisSettings settings) {
        if (!settings.enabled()) {
            return null;
        }
        return client(plugin, settings, keyOf(settings), false);
    }

    private static @Nullable RedisClient client(Plugin plugin, RedisSettings settings, String key,
                                                boolean mayWait) {
        RedisClient existing = CLIENTS.get(key);
        if (existing != null) {
            return existing;
        }
        if (quiet(key)) {
            return null;
        }
        if (!mayWait) {
            openInBackground(plugin, settings, key);
            return null;
        }
        // Off the server thread waiting is allowed, and bounded by the
        // connect timeout; joining an attempt already under way is what keeps
        // it to one pool and one wait.
        return open(plugin, settings, key).join();
    }

    /** Whether a recent failure says not to try this key yet. */
    private static boolean quiet(String key) {
        Long quietUntil = RETRIES.get(key);
        return quietUntil != null && System.currentTimeMillis() < quietUntil;
    }

    private static void openInBackground(Plugin plugin, RedisSettings settings, String key) {
        if (OPENING.containsKey(key)) {
            return;
        }
        try {
            Tasks.of(plugin).runAsync(() -> open(plugin, settings, key));
        } catch (RuntimeException refused) {
            // A plugin on its way down has no scheduler to lend. Whoever asks
            // next schedules again.
        }
    }

    /**
     * The one place a client is opened. Never called with a lock held and
     * never on the server thread.
     *
     * <p>One attempt per key at a time: a caller that finds another thread
     * already connecting waits for that attempt rather than starting a second
     * pool, which during an outage would be every database thread dialling the
     * same dead host at once.
     *
     * <p>A failed attempt is remembered for {@link #RETRY_AFTER_MILLIS} rather
     * than forever: whoever asks next tries again, so a Redis that comes back
     * is picked up without reloading anything. Within that window the answer is
     * {@code null} without a console line, because the line was printed when
     * the attempt actually failed.
     */
    private static CompletableFuture<RedisClient> open(Plugin plugin, RedisSettings settings, String key) {
        CompletableFuture<RedisClient> mine = new CompletableFuture<>();
        CompletableFuture<RedisClient> running = OPENING.putIfAbsent(key, mine);
        if (running != null) {
            return running;
        }
        RedisClient opened = null;
        try {
            RedisClient existing = CLIENTS.get(key);
            if (existing != null || quiet(key)) {
                opened = existing;
            } else {
                opened = connect(plugin, settings, key);
            }
        } finally {
            OPENING.remove(key, mine);
            mine.complete(opened);
        }
        return mine;
    }

    private static @Nullable RedisClient connect(Plugin plugin, RedisSettings settings, String key) {
        Debug debug = Debug.of(plugin);
        int generation = GENERATION.get();
        RedisClient opened;
        try {
            opened = factory.open(settings, "exylia-" + plugin.getName());
        } catch (Throwable unreachable) {
            // Never fatal. A plugin whose Redis is down must still enable,
            // and it will: the database is the truth and it is still there.
            RETRIES.put(key, System.currentTimeMillis() + RETRY_AFTER_MILLIS);
            debug.warn("Redis is configured but could not be reached at " + settings.host()
                    + ':' + settings.port() + " (" + unreachable.getMessage() + ")."
                    + " Continuing without a shared cache: everything works, reads just go"
                    + " to the database. Cross-server changes will not be visible until"
                    + " this is fixed. Trying again in " + (RETRY_AFTER_MILLIS / 1000) + "s.");
            return null;
        }
        RedisClient won = publish(CLIENTS, key, opened, generation);
        if (won != opened) {
            closeQuietly(opened);
            return won;
        }
        if (RETRIES.remove(key) != null) {
            // The recovery is as worth a line as the failure was: without
            // it the last word on Redis in the log is that it was down.
            debug.warn("Redis is answering again at " + settings.host() + ':'
                    + settings.port() + ". Cross-server delivery is back on.");
        }
        return opened;
    }

    /**
     * Publishes what was opened, unless the library shut down meanwhile.
     *
     * <p>Under the same monitor as {@link #shutdown}, so a shutdown cannot
     * slip between the check and the put and leave a pool — or a cache with
     * its own reconnecting subscriber thread — in maps nobody closes again.
     *
     * @return {@code value} when it was published, the earlier entry when one
     *         won, or {@code null} after a shutdown; anything but {@code value}
     *         means the caller closes its own
     */
    private static synchronized <V> @Nullable V publish(Map<String, V> map, String key, V value,
                                                         int generation) {
        if (generation != GENERATION.get()) {
            return null;
        }
        V won = map.putIfAbsent(key, value);
        return won == null ? value : won;
    }

    private static boolean isServerThread() {
        // No server at all is a test without FakeServer, and nothing there
        // is a tick to protect.
        return Bukkit.getServer() != null && Bukkit.isPrimaryThread();
    }

    private static void closeQuietly(RedisClient client) {
        try {
            client.close();
        } catch (Throwable ignored) {
            // A pool that will not close cleanly is not worth failing the
            // caller over: it was never handed to anybody.
        }
    }

    /**
     * Wraps a storage with a cache when there is one to wrap it with.
     *
     * @param storage  the real storage
     * @param cache    the shared cache, or {@code null}
     * @param executor where the cache talks to Redis, off the caller's thread
     * @return the storage a repository should use
     */
    public static @NotNull Storage wrap(@NotNull Storage storage, @Nullable RowCache cache,
                                        @NotNull Executor executor) {
        return cache == null ? storage : new CachedStorage(storage, cache, executor);
    }

    /**
     * Wraps a storage with a cache that is looked up on every operation.
     *
     * <p>For a plugin whose Redis is configured but may not be reachable yet:
     * the storage runs uncached while the supplier answers {@code null} and
     * picks the cache up as soon as it answers one.
     *
     * @param storage  the real storage
     * @param caches   the shared cache, or {@code null} while there is none
     * @param executor where the cache talks to Redis, off the caller's thread
     * @return the storage a repository should use
     */
    public static @NotNull Storage wrap(@NotNull Storage storage,
                                        @NotNull Supplier<@Nullable RowCache> caches,
                                        @NotNull Executor executor) {
        return new CachedStorage(storage, caches, executor);
    }

    /** Closes every connection. Called by ExyliaLib on shutdown. */
    public static synchronized void shutdown() {
        GENERATION.incrementAndGet();
        CACHES.values().forEach(RowCache::close);
        CACHES.clear();
        CLIENTS.values().forEach(RedisRuntime::closeQuietly);
        CLIENTS.clear();
        RETRIES.clear();
    }

    /**
     * Whether Redis is connected, for diagnostics.
     *
     * <p>Any open connection counts, not only the ones a row cache uses: a
     * server whose plugins only publish and subscribe has no cache at all, and
     * reporting that as "off" said its working Redis was down.
     */
    public static boolean isActive() {
        return !CACHES.isEmpty() || !CLIENTS.isEmpty();
    }

    /** Hit rates of every open cache, for the library's own command. */
    public static @NotNull String stats() {
        if (CACHES.isEmpty()) {
            return CLIENTS.isEmpty()
                    ? "no Redis connection is open"
                    : "connected to " + String.join(", ", CLIENTS.keySet())
                            + ", no row cache in use";
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

    /**
     * Test seam: supplies a client without a Redis server.
     *
     * <p>Installing a factory also stops treating the test thread as the
     * server thread: the fake server reports every thread as primary, which
     * would turn each first lookup into a background open the test did not
     * ask about. {@link #installServerThreadForTests} puts the check back.
     */
    public static synchronized void installForTests(@Nullable ClientFactory testFactory) {
        shutdown();
        OPENING.clear();
        factory = testFactory == null ? JedisClient::open : testFactory;
        onServerThread = testFactory == null ? RedisRuntime::isServerThread : () -> false;
    }

    /** Test seam: decides which callers count as the server thread. */
    public static void installServerThreadForTests(@NotNull BooleanSupplier check) {
        onServerThread = check;
    }

    /** How a client is opened, so a test can supply one that needs no server. */
    @FunctionalInterface
    public interface ClientFactory {

        @NotNull RedisClient open(@NotNull RedisSettings settings, @NotNull String name);
    }
}
