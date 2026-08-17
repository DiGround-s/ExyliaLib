package net.exylia.lib.database.internal;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.database.DatabaseException;
import net.exylia.lib.database.DatabaseSettings;
import net.exylia.lib.debug.Debug;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The one connection the whole server shares, and its life.
 *
 * <p>ExyliaCommons opened a pool per plugin, so a server running eight Exylia
 * plugins held eight pools against the same MySQL instance and spent eight
 * times the connections to do it — while the database was already the
 * bottleneck. Here the library owns the connection, reads
 * {@code ExyliaLib/database.yml} for it, and hands plugins repositories that go
 * through it.
 *
 * <h2>Opened when something needs it, not at enable</h2>
 * A server whose plugins store nothing never opens a pool, never creates an H2
 * file and never contacts a MySQL host that may not be up yet. The first
 * repository is what triggers it, and it triggers it in the background: opening
 * a pool means a socket, a handshake and an authentication round trip, none of
 * which the main thread should wait for.
 *
 * <h2>Threads</h2>
 * Safe from any thread. Opening is guarded so that two plugins registering
 * their first repository in the same tick open one pool between them, not two.
 *
 * @since 1.24.0
 */
public final class DatabaseRuntime {

    /** Everything {@link Dialect#of(String)} answers to, for reporting a typo. */
    private static final Set<String> SQL_ENGINES =
            Set.of("h2", "mysql", "mariadb", "postgres", "postgresql", "pgsql");

    private static final Object LOCK = new Object();

    private static volatile Plugin library;
    private static volatile Executor executor;
    private static volatile ConfigFile<DatabaseSettings> config;

    /**
     * Settings that bypass the config file entirely.
     *
     * <p>The test seam, and the only one: tests run against H2 in memory,
     * because a wire-format bug is invisible to a mock by construction and a
     * temporary file is still a file to clean up.
     */
    private static volatile SqlSettings override;

    /**
     * The one in-flight or finished opening.
     *
     * <p>Also the answer to "is it ready", rather than a second field the
     * opening task publishes into. A separate flag written when the task lands
     * can be written <em>after</em> a shutdown that already cleared it — the
     * task was in flight when the shutdown happened — and the library then
     * reports a connection that has been closed. Reading the state off this one
     * reference cannot say that, because a superseded opening is no longer the
     * one anybody is holding.
     */
    private static volatile CompletableFuture<Storage> shared;

    private DatabaseRuntime() {
    }

    /**
     * Binds the runtime to the library and reads its configuration.
     *
     * <p>Called once, from the library's {@code onEnable}. Reading the file is
     * the only thing that happens here: nothing is connected until a plugin
     * asks for a repository.
     *
     * @param plugin the library plugin, which owns the connection and the file
     */
    public static void init(@NotNull Plugin plugin) {
        synchronized (LOCK) {
            library = plugin;
            executor = new TaskExecutor(plugin);
            config = Configs.define(plugin, "database", DatabaseSettings.class).load();
        }
    }

    /**
     * Test seam: binds the runtime to explicit settings, with no config file.
     *
     * @param plugin   the plugin whose scheduler runs the work
     * @param settings where to connect
     */
    public static void installForTests(@NotNull Plugin plugin, @NotNull SqlSettings settings) {
        synchronized (LOCK) {
            shutdownLocked();
            library = plugin;
            executor = new TaskExecutor(plugin);
            override = settings;
        }
    }

    /** Where database work runs: the server's own asynchronous pool. */
    public static @NotNull Executor executor() {
        Executor current = executor;
        if (current == null) {
            throw new IllegalStateException("The database module was used before ExyliaLib enabled."
                    + " Ask for repositories from onEnable, not from a static initialiser.");
        }
        return current;
    }

    /**
     * The shared store, opening it if this is the first time anybody asked.
     *
     * <p>Never blocks. The returned future completes on a background thread
     * once the pool is up, or fails with why it will not be.
     *
     * @return the store
     */
    public static @NotNull CompletableFuture<Storage> storage() {
        CompletableFuture<Storage> existing = shared;
        if (existing != null) {
            return existing;
        }
        synchronized (LOCK) {
            if (shared == null) {
                shared = openAsync();
            }
            return shared;
        }
    }

    /**
     * Whether the connection is open and usable.
     *
     * <p>{@code false} both before anything asked for it and after a failure to
     * open, which are the same thing from a caller's point of view: there is
     * nothing to store into yet.
     */
    public static boolean isReady() {
        CompletableFuture<Storage> current = shared;
        return current != null && current.isDone() && !current.isCompletedExceptionally();
    }

    /** The engine in use, or the one configured but not yet opened. */
    public static @NotNull String engine() {
        SqlSettings forced = override;
        if (forced != null) {
            return forced.engine();
        }
        ConfigFile<DatabaseSettings> file = config;
        return file != null ? file.get().engine() : "unconfigured";
    }

    /**
     * Closes the connection and forgets it.
     *
     * <p>Called when the library disables. A pool that outlives its plugin
     * holds threads and sockets nothing will ever close.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            shutdownLocked();
            library = null;
            executor = null;
            config = null;
            override = null;
        }
    }

    private static void shutdownLocked() {
        CompletableFuture<Storage> current = shared;
        shared = null;
        if (current != null) {
            // whenComplete rather than a null check: an opening still in flight
            // must be closed when it lands, or the pool it produces belongs to
            // nobody and is never shut down.
            current.whenComplete((storage, failure) -> {
                if (storage != null) {
                    storage.close();
                }
            });
        }
    }

    // -------------------------------------------------------------- opening

    private static @NotNull CompletableFuture<Storage> openAsync() {
        Plugin plugin = library;
        Executor where = executor;
        if (plugin == null || where == null) {
            return CompletableFuture.failedFuture(new DatabaseException(
                    "The database module was used before ExyliaLib enabled."
                            + " Ask for repositories from onEnable."));
        }
        CompletableFuture<Storage> future = new CompletableFuture<>();
        try {
            where.execute(() -> {
                try {
                    future.complete(open(plugin));
                } catch (Throwable failure) {
                    // Reported here as well as handed to the future. Every
                    // repository chained onto this will report it too, but the
                    // first plugin to try is not necessarily the one an
                    // operator is looking at, and "the database never opened"
                    // deserves to be said once, plainly, on its own.
                    Debug.of(plugin).error("The shared database could not be opened."
                            + " Every plugin storing anything will fail until this is fixed:"
                            + " " + failure.getMessage(), failure);
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            future.completeExceptionally(new DatabaseException(
                    "The database could not be opened: the work could not be scheduled,"
                            + " which normally means ExyliaLib is being disabled.", rejected));
        }
        return future;
    }

    /**
     * Opens the store the configuration describes.
     *
     * <p>Runs on a background thread and blocks: Hikari opens its first
     * connection eagerly, which is what turns a wrong password into a message
     * at startup rather than an exception on the first player join.
     *
     * <p>Which implementation is chosen is decided exactly once, here. Nothing
     * above this line — no repository, no query, no plugin — knows whether it is
     * talking to a SQL engine or to Mongo, which is the entire point of the
     * {@link Storage} seam.
     */
    private static @NotNull Storage open(@NotNull Plugin plugin) {
        Debug debug = Debug.of(plugin);
        String name = plugin.getName().toLowerCase(Locale.ROOT);
        SqlSettings settings = resolve(plugin, debug);
        Storage storage = isMongo(settings)
                ? new MongoStorage(MongoBackend.open(settings, name), executor(), debug::warn)
                : new SqlStorage(SqlBackend.open(settings, name), executor(), debug::warn);
        debug.log("Database ready: " + settings + ".");
        return storage;
    }

    /**
     * Whether settings name Mongo rather than one of the four SQL engines.
     *
     * <p>Asked of the resolved settings rather than of the config record, so
     * that the test seam — which supplies {@link SqlSettings} directly and never
     * reads a file — reaches the same branch a configured server does.
     */
    private static boolean isMongo(@NotNull SqlSettings settings) {
        String engine = settings.engine().toLowerCase(Locale.ROOT).trim();
        return engine.equals("mongo") || engine.equals("mongodb");
    }

    /**
     * Turns the configuration into connection settings, refusing what cannot
     * work and correcting what is merely misspelled.
     *
     * <p>A typo in an engine name falls back to H2, which is the same place a
     * server with no configuration at all ends up — a mistyped word must not be
     * the reason a server does not start. A name that is merely unsupported does
     * not fall back that way: an operator who configured a real engine and
     * silently got a local H2 file would have this server's data written
     * somewhere nobody would ever think to look for it.
     */
    private static @NotNull SqlSettings resolve(@NotNull Plugin plugin, @NotNull Debug debug) {
        SqlSettings forced = override;
        if (forced != null) {
            return forced;
        }
        ConfigFile<DatabaseSettings> file = config;
        DatabaseSettings values = file != null ? file.get() : new DatabaseSettings();
        if (values.mongo()) {
            return values.toSql(dataFolder(plugin));
        }
        if (!SQL_ENGINES.contains(values.engine())) {
            debug.warn("database.yml asks for the engine \"" + values.type() + "\", which does not"
                    + " exist. Using the embedded h2 database instead. Valid values are:"
                    + " h2, mysql, mariadb, postgresql, mongodb.");
            values = new DatabaseSettings();
        }
        return values.toSql(dataFolder(plugin));
    }

    /**
     * Where an embedded database file goes.
     *
     * <p>The library's own folder, not the calling plugin's: the file backs one
     * database shared by every plugin, and putting it inside whichever plugin
     * happened to ask first would make it look like that plugin's to anybody
     * cleaning up.
     */
    private static @NotNull java.nio.file.Path dataFolder(@NotNull Plugin plugin) {
        java.io.File folder = plugin.getDataFolder();
        if (folder == null) {
            throw new DatabaseException("ExyliaLib has no data folder, so an embedded database"
                    + " has nowhere to live.");
        }
        return folder.toPath();
    }

    /** The store, if it is open, for diagnostics only. */
    public static @Nullable Storage current() {
        CompletableFuture<Storage> current = shared;
        return isReady() ? current.getNow(null) : null;
    }
}
