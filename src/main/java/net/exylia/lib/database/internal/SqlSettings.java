package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a SQL backend connects and how hard it is allowed to try.
 *
 * <p>Plain data. It carries what an operator writes in a config and nothing
 * derived: the JDBC URL, the driver class and the pool shape are the
 * {@link Dialect}'s business, because every one of them differs per engine and
 * none of them should be spelled out in a plugin's {@code storage.yml}.
 *
 * <pre>{@code
 * // networked
 * SqlSettings mysql = SqlSettings.remote("mysql", "10.0.0.5", 3306, "practice", "user", "secret");
 *
 * // embedded, one file next to the plugin
 * SqlSettings h2 = SqlSettings.file("h2", plugin.getDataFolder().toPath().resolve("data"));
 * }</pre>
 *
 * @since 1.24.0
 */
public final class SqlSettings {

    private final String engine;
    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;
    private final Path file;
    private final int poolSize;
    private final Map<String, String> properties;

    private SqlSettings(String engine,
                        @Nullable String host,
                        int port,
                        @Nullable String database,
                        @Nullable String user,
                        @Nullable String password,
                        @Nullable Path file,
                        int poolSize,
                        Map<String, String> properties) {
        this.engine = engine;
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
        this.file = file;
        this.poolSize = poolSize;
        this.properties = Map.copyOf(properties);
    }

    /**
     * Settings for a networked engine.
     *
     * @param engine   {@code mysql}, {@code mariadb} or {@code postgres}
     * @param host     the host name
     * @param port     the port, {@code 0} for the engine's default
     * @param database the schema or database name
     * @param user     the user
     * @param password the password, possibly empty
     * @return the settings
     */
    public static @NotNull SqlSettings remote(@NotNull String engine,
                                              @NotNull String host,
                                              int port,
                                              @NotNull String database,
                                              @NotNull String user,
                                              @NotNull String password) {
        return new SqlSettings(engine, host, port, database, user, password, null, 0, Map.of());
    }

    /**
     * Settings for an embedded engine backed by a file.
     *
     * @param engine the engine, in practice {@code h2}
     * @param file   the database file, without the engine's own suffix
     * @return the settings
     */
    public static @NotNull SqlSettings file(@NotNull String engine, @NotNull Path file) {
        return new SqlSettings(engine, null, 0, null, "sa", "", file, 0, Map.of());
    }

    /**
     * Settings for an embedded engine held only in memory.
     *
     * <p>The whole database disappears with the JVM. For tests, and for nothing
     * else — a plugin that stores a player's data here loses it on restart.
     *
     * @param engine the engine, in practice {@code h2}
     * @param name   the in-memory database name, shared by every connection using it
     * @return the settings
     */
    public static @NotNull SqlSettings memory(@NotNull String engine, @NotNull String name) {
        return new SqlSettings(engine, null, 0, name, "sa", "", null, 0, Map.of());
    }

    /**
     * The same settings with a different pool ceiling.
     *
     * @param maximumPoolSize connections at most, {@code 0} for the dialect's own answer
     * @return a new instance
     */
    public @NotNull SqlSettings poolSize(int maximumPoolSize) {
        return new SqlSettings(engine, host, port, database, user, password, file,
                maximumPoolSize, properties);
    }

    /**
     * The same settings with one extra JDBC URL parameter.
     *
     * <p>Appended after the ones the dialect insists on, so an operator can add
     * something the library does not know about. It can also override one of
     * ours, which is deliberate: a server behind a proxy that needs a different
     * {@code sslMode} should not need a library release.
     *
     * @param key   the parameter name
     * @param value the value
     * @return a new instance
     */
    public @NotNull SqlSettings property(@NotNull String key, @NotNull String value) {
        Map<String, String> merged = new LinkedHashMap<>(properties);
        merged.put(key, value);
        return new SqlSettings(engine, host, port, database, user, password, file, poolSize, merged);
    }

    /** The engine name, as {@link Dialect#of(String)} takes it. */
    public @NotNull String engine() {
        return engine;
    }

    /** The host, or {@code null} for an embedded database. */
    public @Nullable String host() {
        return host;
    }

    /** The port, or {@code 0} when the engine's default should be used. */
    public int port() {
        return port;
    }

    /** The database or schema name, or {@code null} for a file-backed one. */
    public @Nullable String database() {
        return database;
    }

    /** The user, possibly {@code null}. */
    public @Nullable String user() {
        return user;
    }

    /** The password, possibly {@code null}. */
    public @Nullable String password() {
        return password;
    }

    /** The database file, or {@code null} when the database is networked or in memory. */
    public @Nullable Path file() {
        return file;
    }

    /** The pool ceiling an operator asked for, or {@code 0} to let the dialect decide. */
    public int poolSize() {
        return poolSize;
    }

    /** Extra JDBC URL parameters, in the order they were added. */
    public @NotNull Map<String, String> properties() {
        return properties;
    }

    /** Whether the database lives in this process rather than over a socket. */
    public boolean embedded() {
        return host == null;
    }

    /**
     * The port to connect to.
     *
     * @param fallback the engine's default port
     * @return the configured port, or the fallback when none was set
     */
    public int portOr(int fallback) {
        return port > 0 ? port : fallback;
    }

    @Override
    public String toString() {
        // Never the password. This ends up in a debug line on enable, and a
        // credential in a console log is a credential in whatever pastebin the
        // next support ticket links to.
        return "SqlSettings[" + engine + " "
                + (embedded() ? String.valueOf(file != null ? file : database) : host + ":" + port + "/" + database)
                + "]";
    }
}
