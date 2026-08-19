package net.exylia.lib.database;

import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.GatedStorage;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.internal.Storage;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.redis.internal.RowCache;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One plugin's view of its configured database target.
 *
 * <p>Obtained from {@link Databases#of(Plugin)} and normally kept in a field:
 *
 * <pre>{@code
 * private Repository<PlayerStats> stats;
 *
 * @Override
 * public void onEnable() {
 *     stats = Databases.of(this).repository(PlayerStats.class);
 * }
 * }</pre>
 *
 * <p>The client is owned by ExyliaLib, while this view owns one lazy lease on
 * its resolved target. Equal settings share a target; differing settings never
 * do. Disabling the plugin drops its repositories and lease.
 *
 * <h2>Nothing here blocks, including registration</h2>
 * {@link #repository} returns immediately. Behind it, the pool is opened if it
 * is not already and the table is created if it is not already, both on a
 * background thread; operations issued in the meantime are chained onto that
 * work rather than raced against it. So this is correct, in the same tick, with
 * no table in the database yet:
 *
 * <pre>{@code
 * Repository<PlayerStats> stats = Databases.of(this).repository(PlayerStats.class);
 * stats.save(new PlayerStats(uuid, 1200));   // lands after the CREATE TABLE
 * }</pre>
 *
 * <p>The reason it has to work that way: registration happens in
 * {@code onEnable}, on the main thread, and creating a table is a round trip to
 * a machine that may not be this one. Blocking there is a server that takes a
 * second longer to start for every plugin that stores anything, and on an
 * unreachable database it is a server that never starts at all.
 *
 * <h2>One repository per record type</h2>
 * Asking twice for the same class returns the same instance, so a plugin that
 * cannot conveniently keep the reference does not create a second table
 * preparation by asking again.
 *
 * <h2>Threads</h2>
 * Safe from any thread.
 *
 * @since 1.24.0
 */
public final class PluginDatabase {

    private final Plugin plugin;
    private final SqlSettings settings;

    /**
     * This plugin's repositories, by record class.
     *
     * <p>Unbounded on purpose: the key is a loaded {@code Class} and a plugin
     * has a fixed, small number of them. A bounded cache here would mean
     * preparing a table again because a different record was used more recently.
     */
    private final Map<Class<?>, Repository<?>> repositories = new ConcurrentHashMap<>();
    private volatile DatabaseRuntime.Lease lease;
    private boolean released;

    /**
     * The shared cache, resolved lazily and at most once.
     *
     * <p>The flag rather than a null check: a plugin that configured no Redis
     * resolves to null, and without it every repository would try to open a
     * connection again.
     */
    private RowCache cache;
    private boolean cacheResolved;

    PluginDatabase(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.settings = DatabaseRuntime.settings(plugin);
    }

    /** The plugin this view belongs to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** The configured engine for this plugin's target, for diagnostics. */
    public @NotNull String engine() {
        return settings.engine();
    }

    /** Whether this plugin's acquired target is open and usable. */
    public boolean isReady() {
        DatabaseRuntime.Lease current = lease;
        return current != null && current.isReady();
    }

    /**
     * The repository for a record type, creating its table if it is missing.
     *
     * <p>Call this once per record type, from {@code onEnable}. It returns
     * without waiting for anything; see the class documentation for what happens
     * behind it.
     *
     * <p>The record is compiled here, and a record that cannot be stored fails
     * here, synchronously, with a message naming the component at fault. That is
     * deliberate: a missing {@link Table}, two {@link Id}s or a type nothing can
     * encode are mistakes in code, identical on every row and every server, so
     * they are worth one loud failure at enable — where the developer is
     * watching — rather than an exception inside a background write three days
     * later, where nobody is.
     *
     * @param recordType the record class, annotated with {@link Table} and
     *                   carrying exactly one {@link Id}
     * @param <T>        the record type
     * @return the repository, the same instance every time
     * @throws IllegalArgumentException if the class cannot be stored
     */
    @SuppressWarnings("unchecked")
    public <T> @NotNull Repository<T> repository(@NotNull Class<T> recordType) {
        if (released) {
            throw new IllegalStateException("The database view for " + plugin.getName()
                    + " was released because the plugin is disabled.");
        }
        return (Repository<T>) repositories.computeIfAbsent(recordType, this::build);
    }

    /**
     * Whether this plugin has already registered a repository for a record type.
     *
     * @param recordType the record class
     * @return whether it is registered
     */
    public boolean has(@NotNull Class<?> recordType) {
        return repositories.containsKey(recordType);
    }

    /**
     * How many repositories this plugin registered, for diagnostics.
     *
     * @return the count
     */
    public int registered() {
        return repositories.size();
    }

    /**
     * The tables this plugin has registered so far, by table name.
     *
     * <p>Public because a whole-plugin operation — an export, a diagnostic
     * listing — has no other way to find out which tables a plugin owns, and
     * because the answer is only names and repositories, both of which are
     * already public.
     *
     * <h2>It is what has been registered, not what exists</h2>
     * A repository appears here the moment {@link #repository(Class)} is
     * called, and not before. A plugin that registers a record type lazily —
     * on first use, behind a config switch, from a subcommand — has fewer
     * tables here than it will eventually have, and nothing can tell the
     * difference from outside. That is why every caller of this reports the
     * table names it found rather than only their count: a silent short list
     * is an export missing a table, and the only way anybody notices is by
     * reading the names.
     *
     * <p>Ordered by table name so the same plugin lists the same way twice,
     * which a report of what was exported has to.
     *
     * @return the repositories by table name, never {@code null}, possibly
     *         empty
     * @since 1.36.0
     */
    public @NotNull java.util.SortedMap<String, Repository<?>> tables() {
        java.util.SortedMap<String, Repository<?>> found = new java.util.TreeMap<>();
        for (Repository<?> repository : repositories.values()) {
            found.put(repository.table(), repository);
        }
        return java.util.Collections.unmodifiableSortedMap(found);
    }

    /**
     * Forgets this plugin's repositories.
     *
     * <p>Called by the library when the plugin is disabled; a consumer does not
     * need to. The tables stay exactly where they are — this drops the objects
     * that address them, not the data.
     */
    public synchronized void release() {
        released = true;
        repositories.clear();
        if (lease != null) {
            lease.release();
            lease = null;
        }
    }

    private <T> Repository<T> build(Class<T> recordType) {
        // Compiled first, and outside the background work: a record that cannot
        // be stored must fail on the thread that asked, where the stack trace
        // points at the plugin's own onEnable.
        EntityModel<T> model = EntityModel.of(recordType);
        DatabaseRuntime.Lease target = lease();
        Debug debug = Debug.of(plugin);
        return new Repository<>(new GatedStorage(prepare(target, model), target::submit), model,
                // Against the plugin that owns the repository, not the library:
                // the console line has to name whose query broke.
                debug::error);
    }

    /**
     * The shared cache for this plugin, or {@code null} when it configured none.
     *
     * <p>Resolved once, on the first repository, so a plugin with no Redis pays
     * nothing and one with Redis opens a single connection however many record
     * types it registers.
     */
    private synchronized @Nullable RowCache cache() {
        if (!cacheResolved) {
            cacheResolved = true;
            cache = RedisRuntime.cache(plugin, DatabaseRuntime.redis(plugin));
        }
        return cache;
    }

    /**
     * Opens the shared connection if needed and creates the table, in the
     * background.
     *
     * <p>The returned future is what every operation on the repository waits
     * for, so a failure to prepare is a failure of everything that record type
     * does — which is the honest answer. A read answered with an empty list
     * would be indistinguishable from a database that is simply new.
     */
    private CompletableFuture<Storage> prepare(DatabaseRuntime.Lease target, EntityModel<?> model) {
        Debug debug = Debug.of(plugin);
        return target.submit(() -> target.storage().thenApply(opened ->
                // Wrapped before the table is prepared, so preparing is what
                // registers the table for invalidation: a peer's message names
                // a table, and this is the one place that knows this server
                // reads it.
                RedisRuntime.wrap(opened, cache())).thenCompose(storage ->
                storage.prepare(model).thenApply(report -> {
                    // Only the start where something changed is worth a line. On
                    // a server that has been running for months nothing changes
                    // on any start, and a line per table per boot is how a
                    // startup log stops being read at all.
                    String summary = report.summary();
                    if (summary != null) {
                        debug.log("Database schema " + summary + ".");
                    }
                    // A warning rather than a log line, and separate from the
                    // summary: an index that could not be created is not news
                    // about a successful start, it is a table running without an
                    // index the code asks for, and it stays that way until
                    // somebody acts.
                    String blocked = report.blocked();
                    if (blocked != null) {
                        debug.warn(blocked);
                    }
                    return storage;
                }).exceptionallyCompose(failure -> {
                    debug.error("The table for " + model.type().getSimpleName() + " ("
                            + model.table() + ") could not be prepared. Nothing of that type"
                            + " can be read or written until this is fixed: "
                            + failure.getMessage(), failure);
                    return CompletableFuture.failedFuture(failure);
                })));
    }

    private synchronized DatabaseRuntime.Lease lease() {
        if (lease == null) {
            if (released) {
                throw new IllegalStateException("The database view for " + plugin.getName()
                        + " was released because the plugin is disabled.");
            }
            lease = DatabaseRuntime.acquire(plugin, settings);
        }
        return lease;
    }

    @Override
    public String toString() {
        return "PluginDatabase[" + plugin.getName() + ", " + repositories.size() + " repositories]";
    }
}
