package net.exylia.lib.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Another plugin's database, opened read-only for an importer: its SQLite
 * file or its MySQL server, from the values in that plugin's config.
 *
 * <pre>{@code
 * // off the main thread: an import reads everything at once
 * try (ForeignDatabase old = ForeignDatabase.sqlite(dataFolder.resolve("../Jobs/jobs.sqlite.db"))) {
 *     String table = old.table("jobs");                    // the real name, any case; null when absent
 *     for (Map<String, Object> row : old.rows(table)) {
 *         String uuid = ForeignDatabase.text(row, "player_uuid");
 *         long level = ForeignDatabase.number(row, "level", 1);
 *     }
 *     List<Map<String, Object>> top = old.query("SELECT * FROM jobs WHERE level > ?", 10);
 * }
 * }</pre>
 *
 * <p><b>Read-only.</b> SQLite is opened with {@code SQLITE_OPEN_READONLY} and
 * MySQL connections are marked read-only, so an import never changes the old
 * plugin's data. The drivers are the ones Paper ships; nothing is pooled,
 * because an import opens one connection, reads and closes it.
 *
 * <p><b>Threads.</b> Every call blocks on the database: run it off the main
 * thread ({@code Tasks.runAsync}). One instance is used by one thread at a
 * time. Rows come back as maps keyed by lower-cased column labels; read their
 * values with {@link #text}, {@link #number} and {@link #bytes}, which accept
 * whatever type each driver hands back.
 *
 * <p>Holds no palette-derived state: there is nothing to reload.
 *
 * @since 1.480.0
 */
public final class ForeignDatabase implements AutoCloseable {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final Connection connection;
    private @Nullable Map<String, List<String>> tables;

    ForeignDatabase(@NotNull Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Opens a SQLite file read-only.
     *
     * @param file the database file
     * @return the open database; close it when done
     * @throws SQLException when the file cannot be opened (missing, not SQLite, no driver)
     */
    public static @NotNull ForeignDatabase sqlite(@NotNull Path file) throws SQLException {
        if (!java.nio.file.Files.isRegularFile(file)) {
            // The driver would create an empty file; an import of nothing should say so instead.
            throw new SQLException("No SQLite file at " + file.toAbsolutePath());
        }
        Properties properties = new Properties();
        properties.setProperty("open_mode", "1");   // SQLITE_OPEN_READONLY
        return new ForeignDatabase(DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), properties));
    }

    /**
     * Opens a MySQL or MariaDB database read-only.
     *
     * @param host     the host, or {@code host:port} (3306 when no port is written)
     * @param database the schema
     * @param user     the user
     * @param password the password
     * @return the open database; close it when done
     * @throws SQLException when the server cannot be reached or refuses the login
     */
    public static @NotNull ForeignDatabase mysql(@NotNull String host, @NotNull String database,
                                                 @NotNull String user, @NotNull String password)
            throws SQLException {
        String address = host.contains(":") ? host : host + ":3306";
        Connection connection = DriverManager.getConnection("jdbc:mysql://" + address + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true", user, password);
        connection.setReadOnly(true);
        return new ForeignDatabase(connection);
    }

    /**
     * Opens a MySQL or MariaDB database read-only, the port apart.
     *
     * @see #mysql(String, String, String, String)
     */
    public static @NotNull ForeignDatabase mysql(@NotNull String host, int port, @NotNull String database,
                                                 @NotNull String user, @NotNull String password)
            throws SQLException {
        return mysql(host + ":" + port, database, user, password);
    }

    /** The connection itself, for what the helpers here do not cover. Read-only. */
    public @NotNull Connection connection() {
        return connection;
    }

    /**
     * Every table and its columns, column names lower-cased. Read once and
     * remembered.
     *
     * @return table name, as the database spells it, to its columns
     */
    public @NotNull Map<String, List<String>> tables() throws SQLException {
        Map<String, List<String>> known = tables;
        if (known != null) return known;
        Map<String, List<String>> found = new LinkedHashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet names = meta.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (names.next()) found.put(names.getString("TABLE_NAME"), new ArrayList<>());
        }
        for (Map.Entry<String, List<String>> table : found.entrySet()) {
            try (ResultSet columns = meta.getColumns(connection.getCatalog(), null, table.getKey(), "%")) {
                while (columns.next()) {
                    table.getValue().add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            table.setValue(Collections.unmodifiableList(table.getValue()));
        }
        tables = known = Collections.unmodifiableMap(found);
        return known;
    }

    /**
     * A table's real name for a name written in any case.
     *
     * @param wanted the name
     * @return the name as the database spells it, or {@code null} when there is no such table
     */
    public @Nullable String table(@NotNull String wanted) throws SQLException {
        return find(tables(), wanted);
    }

    static @Nullable String find(Map<String, List<String>> tables, String wanted) {
        for (String name : tables.keySet()) if (name.equalsIgnoreCase(wanted)) return name;
        return null;
    }

    /**
     * Every row of a table.
     *
     * @param table a name from {@link #tables()} or {@link #table(String)}; {@code null} answers no rows
     * @return the rows, column labels lower-cased
     * @throws SQLException          when the read fails
     * @throws IllegalArgumentException when the name is not a plain table name
     */
    public @NotNull List<Map<String, Object>> rows(@Nullable String table) throws SQLException {
        if (table == null) return new ArrayList<>();
        return query("SELECT * FROM " + checkTable(table));
    }

    /**
     * Runs a query with {@code ?} parameters.
     *
     * @param sql        the statement; table names written into it are the caller's to trust
     * @param parameters the values for its {@code ?}, in order
     * @return the rows, column labels lower-cased
     */
    public @NotNull List<Map<String, Object>> query(@NotNull String sql, Object... parameters) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                net.exylia.lib.database.internal.SqlBackend.setValue(statement, index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= meta.getColumnCount(); column++) {
                        row.put(meta.getColumnLabel(column).toLowerCase(Locale.ROOT), result.getObject(column));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    static String checkTable(String table) {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Unexpected table name " + table);
        }
        return table;
    }

    /** Closes the connection. Idempotent. */
    @Override
    public void close() throws SQLException {
        connection.close();
    }

    // ------------------------------------------------------------- values

    /**
     * A column as text, whatever the driver handed back: bytes are read as
     * UTF-8, anything else through {@code toString}.
     *
     * @return the text, or {@code null} when the column is absent or null
     */
    public static @Nullable String text(@NotNull Map<String, Object> row, @NotNull String column) {
        Object value = row.get(column);
        if (value == null) return null;
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return value.toString();
    }

    /**
     * A column as a whole number: numbers truncated toward negative infinity,
     * booleans as {@code 1}/{@code 0}, numeric text parsed.
     *
     * @param fallback what an absent, null or non-numeric column reads as
     */
    public static long number(@NotNull Map<String, Object> row, @NotNull String column, long fallback) {
        double value = decimal(row, column, Double.NaN);
        return Double.isNaN(value) ? fallback : (long) Math.floor(value);
    }

    /**
     * A column as a decimal number: numbers as they are, booleans as
     * {@code 1}/{@code 0}, numeric text parsed.
     *
     * @param fallback what an absent, null or non-numeric column reads as
     */
    public static double decimal(@NotNull Map<String, Object> row, @NotNull String column, double fallback) {
        Object value = row.get(column);
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(text(row, column).trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException notNumber) {
            return fallback;
        }
    }

    /**
     * A column as bytes, from a byte array or a {@link Blob}.
     *
     * @return the bytes, or {@code null} when the column holds neither or cannot be read
     */
    public static byte @Nullable [] bytes(@NotNull Map<String, Object> row, @NotNull String column) {
        Object value = row.get(column);
        if (value instanceof byte[] bytes) return bytes;
        if (value instanceof Blob blob) {
            try {
                return blob.getBytes(1, (int) blob.length());
            } catch (SQLException unreadable) {
                return null;
            }
        }
        return null;
    }
}
