package net.exylia.lib.database.internal;

import net.exylia.lib.config.Configs;
import net.exylia.lib.database.DatabaseException;
import net.exylia.lib.database.DatabaseSettings;
import net.exylia.lib.debug.Debug;
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

/** Owns the ref-counted database targets used by consumer plugins. */
public final class DatabaseRuntime {

    private static final Set<String> SQL_ENGINES =
            Set.of("h2", "mysql", "mariadb", "postgres", "postgresql", "pgsql");
    private static final Object LOCK = new Object();

    private static volatile Plugin library;
    private static volatile Executor executor;
    private static Map<TargetKey, Target> targets = new LinkedHashMap<>();
    private static volatile Map<String, SqlSettings> overrides = Map.of();

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
        }
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

        DatabaseSettings values = Configs.define(plugin, "database", DatabaseSettings.class).load().get();
        Debug debug = Debug.of(plugin);
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
        private int owners;

        private Target(TargetKey key, SqlSettings settings, Plugin owner) {
            this.key = key;
            this.settings = settings;
            this.owner = owner;
        }

        private synchronized @NotNull CompletableFuture<Storage> storage() {
            if (storage == null) {
                storage = openAsync(owner, settings, key.poolName());
            }
            return storage;
        }

        private synchronized boolean isReady() {
            return storage != null && storage.isDone() && !storage.isCompletedExceptionally();
        }

        private synchronized void closeWhenFinished() {
            if (storage != null) {
                storage.whenComplete((opened, failure) -> {
                    if (opened != null) {
                        opened.close();
                    }
                });
            }
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

    private static @NotNull Executor executor() {
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
