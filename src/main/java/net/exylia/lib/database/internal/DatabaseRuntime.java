package net.exylia.lib.database.internal;

import net.exylia.lib.config.Configs;
import net.exylia.lib.database.DatabaseException;
import net.exylia.lib.database.DatabaseSettings;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.RedisSettings;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Owns the ref-counted database targets used by consumer plugins. */
public final class DatabaseRuntime {

    private static final Set<String> SQL_ENGINES =
            Set.of("h2", "mysql", "mariadb", "postgres", "postgresql", "pgsql");
    private static final Object LOCK = new Object();

    /** How long a failed connection is remembered before it is tried again. */
    private static final long RETRY_AFTER_MILLIS = 30_000L;

    /** Plugins already told about the embedded file they left behind. */
    private static final Set<String> STRANDED_REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static volatile Plugin library;
    private static volatile Executor executor;
    private static Map<TargetKey, Target> targets = new LinkedHashMap<>();
    private static volatile Map<String, SqlSettings> overrides = Map.of();

    /** Test seam: the Redis block a forced datasource pretends to have read. */
    private static volatile RedisSettings redisOverride;

    private DatabaseRuntime() {
        throw new AssertionError("No instances.");
    }

    /** Starts lifecycle support without loading any consumer configuration. */
    public static void init(@NotNull Plugin plugin) {
        synchronized (LOCK) {
            library = plugin;
            executor = new TaskExecutor(plugin);
        }
    }

    /** Test seam: supplies one datasource to every plugin. */
    public static void installForTests(@NotNull Plugin plugin, @NotNull SqlSettings settings) {
        installForTests(plugin, Map.of("*", settings));
    }

    /** Test seam: supplies datasources by plugin name. */
    public static void installForTests(@NotNull Plugin plugin, @NotNull Map<String, SqlSettings> settings) {
        synchronized (LOCK) {
            shutdownLocked();
            library = plugin;
            executor = new TaskExecutor(plugin);
            overrides = Map.copyOf(settings);
            redisOverride = null;
        }
    }

    /** Test seam: the Redis block forced datasources report. */
    public static void installRedisForTests(@Nullable RedisSettings settings) {
        redisOverride = settings;
    }

    /** Resolves and loads the configuration owned by this consumer plugin. */
    public static @NotNull SqlSettings settings(@NotNull Plugin plugin) {
        requireStarted();
        SqlSettings forced = overrides.get(plugin.getName());
        if (forced == null) {
            forced = overrides.get("*");
        }
        if (forced != null) {
            return forced;
        }
        return resolve(plugin).toSql(dataFolder(plugin));
    }

    /**
     * The shared cache settings this consumer asked for.
     *
     * <p>Separate from {@link #settings} because a {@link SqlSettings} is what
     * identifies a datasource, and two plugins pointed at the same database
     * through different Redis servers are still one datasource. The config file
     * is read once and cached by the config module, so asking twice is a map
     * lookup rather than a second parse.
     *
     * @param plugin the consumer
     * @return its Redis block, disabled when it configured none
     */
    public static @NotNull RedisSettings redis(@NotNull Plugin plugin) {
        requireStarted();
        if (!overrides.isEmpty()) {
            // A forced datasource is a test fixture, which names no config
            // file. Tests that want a cache supply its settings directly.
            return redisOverride == null ? new RedisSettings() : redisOverride;
        }
        DatabaseSettings.Database block = resolve(plugin).database();
        RedisSettings redis = block == null ? null : block.redis();
        return redis == null ? new RedisSettings() : redis;
    }

    /** Reads and validates this consumer's {@code database.yml}. */
    private static DatabaseSettings resolve(Plugin plugin) {
        DatabaseSettings values = Configs.define(plugin, "database", DatabaseSettings.class)
                .version(2)
                .migration(1, DatabaseRuntime::nest)
                .load().get();

        if (values.mongo() || SQL_ENGINES.contains(values.engine())) {
            warnStrandedEmbedded(plugin, values);
            return values;
        }
        // An error rather than a warning: the plugin keeps running, but every
        // row it was meant to send to a server is landing in a local file
        // instead, which is the kind of thing that is only noticed once the
        // data is needed somewhere else.
        Debug.of(plugin).error("database.yml asks for the engine \"" + values.engine()
                + "\", which does not exist, so this plugin is storing everything in its own"
                + " embedded h2 file and nothing it writes reaches a database server."
                + " Valid values are: h2, mysql, mariadb, postgresql, mongodb.");
        // The engine falls back; everything else the owner configured, the
        // Redis block included, is still theirs and still honoured.
        return new DatabaseSettings(new DatabaseSettings.Database("h2",
                values.database().settings(), values.database().h2(),
                values.database().mysql(), values.database().mariadb(),
                values.database().postgresql(), values.database().mongodb(),
                values.database().redis()));
    }

    /**
     * Says so when a plugin on a database server still has its old H2 file.
     *
     * <p>A plugin that was moved from {@code h2} to {@code mysql} leaves its
     * embedded file behind, full of the rows it held on the day of the move.
     * Nothing opens it again, so it is harmless until somebody wonders where
     * the missing history went, or moves the server and copies the file along
     * with it. One line at startup is what turns it from a discovery into a
     * decision: import it or delete it.
     *
     * @param plugin the consumer
     * @param values its configuration, already known to name a valid engine
     */
    private static void warnStrandedEmbedded(Plugin plugin, DatabaseSettings values) {
        // Configuration is resolved once for the datasource and again for the
        // Redis block, and both land here: the line is worth one printing.
        if (values.embedded() || !STRANDED_REPORTED.add(plugin.getName())) {
            return;
        }
        Path file = embeddedFile(plugin, values);
        if (file == null || !java.nio.file.Files.exists(file)) {
            return;
        }
        Debug.of(plugin).warn("This plugin stores its data in " + values.engine()
                + ", but the embedded database it used before is still on disk at "
                + file + ". Nothing reads it: whatever it holds is not in "
                + values.engine() + ". Import it with \"/exylialib import\" or delete it.");
    }

    /** Where this plugin's embedded file is, or would be. */
    private static @Nullable Path embeddedFile(Plugin plugin, DatabaseSettings values) {
        DatabaseSettings.Database block = values.database();
        DatabaseSettings.H2 h2 = block == null ? null : block.h2();
        String name = h2 == null || h2.file() == null || h2.file().isBlank()
                ? "database/h2" : h2.file();
        try {
            // The extension H2 itself adds; the configured name carries none.
            return dataFolder(plugin).resolve(name + ".mv.db");
        } catch (RuntimeException noFolder) {
            return null;
        }
    }

    /**
     * Moves a flat {@code database.yml} under the {@code database:} block.
     *
     * <p>A file written by ExyliaCommons already nests, so it arrives here
     * untouched and keeps every credential its owner set. A file written by
     * ExyliaLib 1.24 to 1.30 was flat, and without this its keys would be pruned
     * as ones the schema no longer owns — taking a MySQL password with them.
     *
     * <p>Every value is read before anything is written, because the old flat
     * {@code database:} key is the new block's own name: renaming it one key at
     * a time would drop whatever the previous rename had just put there.
     */
    private static void nest(@NotNull net.exylia.lib.config.MutableConfig data) {
        // The marker of the flat layout. A nested file has a section here, and a
        // section is not a value, so it is left alone.
        Object flatType = data.get("type");
        if (flatType == null) {
            return;
        }

        // The flat layout had one set of connection fields, so they belong to
        // whichever engine the file names. Sending them to mysql regardless
        // would hand a postgresql server an empty block and lose the password.
        String server = switch (String.valueOf(flatType).toLowerCase(Locale.ROOT).trim()) {
            case "mariadb" -> "mariadb";
            case "postgres", "postgresql", "pgsql" -> "postgresql";
            case "mongo", "mongodb" -> "mongodb";
            default -> "mysql";
        };

        Map<String, String> moves = new LinkedHashMap<>();
        moves.put("type", "database.type");
        moves.put("file", "database.h2.file");
        moves.put("pool-size", "database.settings.pool-size");
        moves.put("host", "database." + server + ".host");
        moves.put("port", "database." + server + ".port");
        moves.put("database", "database." + server + ".database");
        moves.put("user", "database." + server + ".username");
        moves.put("password", "database." + server + ".password");

        Map<String, Object> carried = new LinkedHashMap<>();
        moves.forEach((from, to) -> {
            Object value = data.get(from);
            if (value != null) {
                carried.put(to, value);
            }
            data.remove(from);
        });
        carried.forEach(data::set);
    }

    /** Acquires a target lease, opening nothing until its first repository needs storage. */
    public static @NotNull Lease acquire(@NotNull Plugin plugin, @NotNull SqlSettings settings) {
        TargetKey key = TargetKey.of(settings);
        synchronized (LOCK) {
            requireStarted();
            Target target = targets.get(key);
            if (target == null) {
                target = new Target(key, settings, plugin);
                targets.put(key, target);
            }
            target.owners++;
            return new Lease(target);
        }
    }

    /** Whether any active target is open and usable. */
    public static boolean isReady() {
        synchronized (LOCK) {
            return targets.values().stream().anyMatch(Target::isReady);
        }
    }

    /** One engine only when exactly one target is active; otherwise an honest aggregate. */
    public static @NotNull String engine() {
        synchronized (LOCK) {
            if (targets.isEmpty()) {
                return overrides.values().stream()
                        .map(SqlSettings::engine)
                        .map(DatabaseRuntime::canonicalEngine)
                        .distinct()
                        .reduce((first, second) -> "multiple")
                        .orElse("unconfigured");
            }
            if (targets.size() > 1) {
                return "multiple";
            }
            return targets.values().iterator().next().settings.engine();
        }
    }

    /** Closes every active target and forgets lifecycle state. */
    public static void shutdown() {
        synchronized (LOCK) {
            shutdownLocked();
            library = null;
            executor = null;
            overrides = Map.of();
        }
    }

    /** The number of active targets, for tests and internal diagnostics. */
    public static int targetCount() {
        synchronized (LOCK) {
            return targets.size();
        }
    }

    /**
     * The sole target's storage, retained for internal tests that verify a
     * repository facade cannot close its shared storage.
     */
    public static @NotNull CompletableFuture<Storage> storage() {
        synchronized (LOCK) {
            if (targets.size() != 1) {
                throw new IllegalStateException("No single database target is active.");
            }
            return targets.values().iterator().next().storage();
        }
    }

    /** The only handle a plugin view holds for a shared target. */
    public static final class Lease {
        private final Target target;
        private boolean released;

        private Lease(Target target) {
            this.target = target;
        }

        public @NotNull CompletableFuture<Storage> storage() {
            return target.storage();
        }

        public boolean isReady() {
            return target.isReady();
        }

        /** Whether the last attempt to open this target failed. */
        public boolean isFailed() {
            return target.isFailed();
        }

        public <T> @NotNull CompletableFuture<T> submit(@NotNull Supplier<CompletableFuture<T>> operation) {
            return target.submit(operation);
        }

        public void release() {
            synchronized (LOCK) {
                if (released) {
                    return;
                }
                released = true;
                if (--target.owners == 0 && targets.remove(target.key, target)) {
                    target.closeWhenFinished();
                }
            }
        }
    }

    private static final class Target {
        private final TargetKey key;
        private final SqlSettings settings;
        private final Plugin owner;
        private CompletableFuture<Storage> storage;
        private long attempted;
        private int owners;
        private int operations;
        private boolean closing;
        private boolean closed;

        private Target(TargetKey key, SqlSettings settings, Plugin owner) {
            this.key = key;
            this.settings = settings;
            this.owner = owner;
        }

        /**
         * The open connection, opening it the first time and again after a
         * failure.
         *
         * <p>A database that was down when the server started used to stay
         * down for the rest of the run: the failed attempt was kept and handed
         * to every later call, so a plugin came back only when somebody
         * reloaded it. Retrying costs one connection attempt per
         * {@link #RETRY_AFTER_MILLIS} — the calls in between still fail fast on
         * the last failure, and still say so — and a server that starts before
         * its MariaDB does heals itself.
         */
        private synchronized @NotNull CompletableFuture<Storage> storage() {
            if (storage == null || retryDue()) {
                attempted = System.currentTimeMillis();
                storage = openAsync(owner, settings, key.poolName());
            }
            return storage;
        }

        private boolean retryDue() {
            return !closing && !closed
                    && storage.isCompletedExceptionally()
                    && System.currentTimeMillis() - attempted >= RETRY_AFTER_MILLIS;
        }

        private synchronized boolean isReady() {
            return storage != null && storage.isDone() && !storage.isCompletedExceptionally();
        }

        private synchronized boolean isFailed() {
            return storage != null && storage.isCompletedExceptionally();
        }

        private synchronized <T> @NotNull CompletableFuture<T> submit(
                @NotNull Supplier<CompletableFuture<T>> operation) {
            if (closing) {
                return CompletableFuture.failedFuture(new DatabaseException(
                        "The database target is closing because its last plugin was disabled."));
            }
            operations++;
            CompletableFuture<T> future;
            try {
                future = operation.get();
            } catch (Throwable failure) {
                future = CompletableFuture.failedFuture(failure);
            }
            future.whenComplete((ignored, failure) -> completeOperation());
            return future;
        }

        private synchronized void closeWhenFinished() {
            closing = true;
            closeIfIdle();
        }

        private synchronized void completeOperation() {
            operations--;
            closeIfIdle();
        }

        private void closeIfIdle() {
            if (!closing || operations != 0 || closed || storage == null) {
                return;
            }
            closed = true;
            storage.whenComplete((opened, failure) -> {
                if (opened != null) {
                    opened.close();
                }
            });
        }
    }

    private static final class TargetKey {
        private final String engine;
        private final String host;
        private final int port;
        private final String database;
        private final String user;
        private final String password;
        private final Path file;
        private final int poolSize;
        private final Map<String, String> properties;

        private TargetKey(SqlSettings settings) {
            this.engine = canonicalEngine(settings.engine());
            this.host = canonicalHost(settings.host());
            this.port = settings.host() == null ? 0 : settings.portOr(defaultPort(engine));
            this.database = settings.database();
            this.user = settings.user();
            this.password = settings.password();
            this.file = settings.file() == null ? null : settings.file().toAbsolutePath().normalize();
            this.poolSize = settings.poolSize();
            this.properties = Map.copyOf(settings.properties());
        }

        private static @NotNull TargetKey of(@NotNull SqlSettings settings) {
            return new TargetKey(settings);
        }

        private @NotNull String poolName() {
            return engine + "-" + Integer.toUnsignedString(hashCode(), 36);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof TargetKey key)) {
                return false;
            }
            return port == key.port && poolSize == key.poolSize
                    && java.util.Objects.equals(engine, key.engine)
                    && java.util.Objects.equals(host, key.host)
                    && java.util.Objects.equals(database, key.database)
                    && java.util.Objects.equals(user, key.user)
                    && java.util.Objects.equals(password, key.password)
                    && java.util.Objects.equals(file, key.file)
                    && java.util.Objects.equals(properties, key.properties);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(engine, host, port, database, user, password, file, poolSize, properties);
        }

        @Override
        public String toString() {
            return "DatabaseTarget[" + engine + " "
                    + (file != null ? file : displayHost(host) + ":" + port + "/" + database) + "]";
        }
    }

    private static @NotNull CompletableFuture<Storage> openAsync(@NotNull Plugin plugin,
                                                                    @NotNull SqlSettings settings,
                                                                    @NotNull String name) {
        Executor where = executor;
        if (where == null) {
            return CompletableFuture.failedFuture(new DatabaseException(
                    "The database module was used before ExyliaLib enabled."
                            + " Ask for repositories from onEnable."));
        }
        CompletableFuture<Storage> future = new CompletableFuture<>();
        try {
            where.execute(() -> {
                try {
                    Debug debug = Debug.of(plugin);
                    Storage opened = isMongo(settings)
                            ? new MongoStorage(MongoBackend.open(settings, name), executor(), debug::warn)
                            : new SqlStorage(SqlBackend.open(settings, name), executor(), debug::warn);
                    debug.log("Database ready: " + settings + ".");
                    future.complete(opened);
                } catch (Throwable failure) {
                    Debug.of(plugin).error("The database target could not be opened: "
                            + failure.getMessage(), failure);
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
     * The background executor every storage runs its blocking work on.
     *
     * <p>Public for the Redis cache, whose round trips must leave the calling
     * thread exactly as a query does.
     */
    public static @NotNull Executor executor() {
        requireStarted();
        return executor;
    }

    private static void requireStarted() {
        if (library == null || executor == null) {
            throw new IllegalStateException("The database module was used before ExyliaLib enabled."
                    + " Ask for repositories from onEnable, not from a static initialiser.");
        }
    }

    private static boolean isMongo(@NotNull SqlSettings settings) {
        String engine = canonicalEngine(settings.engine());
        return engine.equals("mongodb");
    }

    private static @NotNull String canonicalEngine(@NotNull String engine) {
        return switch (engine.toLowerCase(Locale.ROOT).trim()) {
            case "postgres", "pgsql" -> "postgresql";
            case "mongo" -> "mongodb";
            default -> engine.toLowerCase(Locale.ROOT).trim();
        };
    }

    private static int defaultPort(@NotNull String engine) {
        return switch (engine) {
            case "mysql", "mariadb" -> 3306;
            case "postgresql" -> 5432;
            case "mongodb" -> 27017;
            default -> 0;
        };
    }

    private static @Nullable String canonicalHost(@Nullable String host) {
        if (host == null || isMongoUri(host)) {
            return host;
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static @NotNull String displayHost(@Nullable String host) {
        return host != null && isMongoUri(host) ? MongoBackend.redact(host) : String.valueOf(host);
    }

    private static boolean isMongoUri(@NotNull String host) {
        return host.startsWith("mongodb://") || host.startsWith("mongodb+srv://");
    }

    private static @NotNull Path dataFolder(@NotNull Plugin plugin) {
        java.io.File folder = plugin.getDataFolder();
        if (folder == null) {
            throw new DatabaseException(plugin.getName() + " has no data folder, so its embedded database"
                    + " has nowhere to live.");
        }
        return folder.toPath();
    }

    private static void shutdownLocked() {
        for (Target target : targets.values()) {
            target.closeWhenFinished();
        }
        targets = new LinkedHashMap<>();
    }

    /** The one ready storage, when exactly one target exists, for internal diagnostics. */
    public static @Nullable Storage current() {
        synchronized (LOCK) {
            if (targets.size() != 1) {
                return null;
            }
            Target target = targets.values().iterator().next();
            return target.isReady() ? target.storage.getNow(null) : null;
        }
    }
}
