package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL 8, through {@code mysql-connector-j}.
 *
 * <p>The engine most Exylia servers actually run, and the one with the most
 * ways to be quietly wrong. Everything below was checked against a live server
 * rather than against the manual.
 *
 * <h2>Why MySQL and MariaDB are two classes</h2>
 * They diverged on the exact statement this library writes most often. MySQL
 * 8.0.20 deprecated {@code VALUES(col)} inside {@code ON DUPLICATE KEY UPDATE}
 * and wants a row alias instead ({@code ... AS new ... col = new.col});
 * MariaDB does not implement that alias and fails to <em>parse</em> it. There
 * is no string that satisfies both, so there are two classes. Sharing one and
 * picking at runtime would mean the deprecation warning on every write on one
 * engine or a syntax error on every write on the other.
 *
 * <h2>Types</h2>
 * No {@code BOOLEAN} — the word is accepted and silently means
 * {@code TINYINT(1)}, so it is written out. {@code FLOAT} is 4 bytes here and 8
 * on H2 and Postgres, which is why {@link AnsiDialect} never emits it. No
 * {@code uuid} type at all, which is half the reason a UUID is stored as text
 * everywhere.
 *
 * <h2>{@code CREATE INDEX IF NOT EXISTS}</h2>
 * Not supported. Verified: it is a syntax error, not a no-op. The schema code
 * therefore asks {@code DatabaseMetaData} first and treats error 1061 as
 * success if it lost the race anyway.
 *
 * @see Dialect
 */
class MySQLDialect extends AnsiDialect {

    static final MySQLDialect INSTANCE = new MySQLDialect();

    /** Error 1061: duplicate key name — what a second {@code CREATE INDEX} gets. */
    private static final int DUPLICATE_INDEX = 1061;

    /** Error 1060: duplicate column name — what a second {@code ADD COLUMN} gets. */
    private static final int DUPLICATE_COLUMN = 1060;

    MySQLDialect() {
    }

    @Override
    public @NotNull String id() {
        return "mysql";
    }

    /**
     * {@code MODIFY}, because MySQL cannot drop a constraint on its own.
     *
     * <p>The column has to be restated in full, so the type the driver reports
     * is repeated back. Everything omitted from a {@code MODIFY} is reset to
     * its default, which here is exactly the one thing being changed.
     */
    @Override
    public @NotNull String dropNotNull(@NotNull String table, @NotNull String column,
                                       @NotNull String type) {
        return "ALTER TABLE " + quote(identifier(table)) + " MODIFY " + quote(column)
                + " " + type + " NULL";
    }

    @Override
    public @NotNull String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull SqlSettings settings) {
        return "jdbc:mysql://" + settings.host() + ":" + settings.portOr(3306)
                + "/" + settings.database() + query(urlParameters(), settings);
    }

    /**
     * The parameters that are not optional.
     *
     * <p>Every one of them fixes something measured:
     * <ul>
     *   <li>{@code rewriteBatchedStatements} turns a batch of inserts into one
     *       multi-row statement instead of one round trip per row. Measured at
     *       8.8x on a 1000-row batch. Off by default, so a batch without it is
     *       a loop with extra steps.</li>
     *   <li>{@code characterEncoding=UTF-8} — a chat message or an item name
     *       with an emoji in it is four bytes per character, and the older
     *       three-byte {@code utf8} truncates the row at the first one. The
     *       value is a Java encoding name, not a MySQL charset name: connector-j
     *       maps {@code UTF-8} onto {@code utf8mb4} on MySQL 8, and rejects the
     *       literal {@code utf8mb4} outright since connector 9 with
     *       "Unsupported character encoding".</li>
     *   <li>{@code connectionTimeZone=SERVER}, not the {@code serverTimezone}
     *       every old guide still shows: that name is deprecated in
     *       connector 8.x and dropped later.</li>
     *   <li>{@code sslMode=PREFERRED} rather than {@code useSSL=false}, which
     *       is likewise deprecated. Prefer, not require: a local MySQL usually
     *       has no certificate, and demanding one there means the server does
     *       not start.</li>
     *   <li>{@code allowPublicKeyRetrieval=true} — without it,
     *       {@code caching_sha2_password} (the default since MySQL 8) refuses
     *       the very first connection on an unencrypted link, with an error
     *       that names RSA and not the password.</li>
     * </ul>
     *
     * <p>Overridable because MariaDB's driver takes a different set and rejects
     * some of these outright.
     */
    @NotNull Map<String, String> urlParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("rewriteBatchedStatements", "true");
        parameters.put("characterEncoding", "UTF-8");
        parameters.put("connectionTimeZone", "SERVER");
        parameters.put("sslMode", "PREFERRED");
        parameters.put("allowPublicKeyRetrieval", "true");
        return parameters;
    }

    @Override
    public @NotNull PoolProfile poolProfile(@NotNull SqlSettings settings) {
        int size = settings.poolSize() > 0 ? settings.poolSize() : 8;
        // Lifetime under MySQL's own wait_timeout (28800s by default, and far
        // lower behind most proxies): a connection the server closed and the
        // pool still believes in surfaces as a broken pipe on a player's join,
        // not on the idle minute when it actually died.
        return new PoolProfile(size, Math.min(2, size), 1_800_000L, 300_000L);
    }

    @Override
    public @NotNull String quote(@NotNull String identifier) {
        // Backticks, not double quotes: double quotes are string literals here
        // unless ANSI_QUOTES is in the session's sql_mode, which is not
        // something a library gets to assume about somebody else's server.
        return '`' + identifier.replace("`", "``") + '`';
    }

    @Override
    public @NotNull String booleanType() {
        // BOOLEAN is accepted and silently stored as this. Writing it out keeps
        // a dumped schema honest about what the column really is.
        return "TINYINT(1)";
    }

    @Override
    public @NotNull String unboundedTextType() {
        // TEXT here is 65535 *bytes*, which at four bytes per character is
        // ~16k characters — a serialised inventory overruns it and MySQL
        // truncates the value rather than refusing it. LONGTEXT is 4GB.
        return "LONGTEXT";
    }

    @Override
    public @NotNull String tableSuffix() {
        // InnoDB for transactions and row locking; utf8mb4 so an emoji in a
        // display name is storable. Named explicitly because a server whose
        // default engine or charset was changed years ago would otherwise
        // create a table nothing else in the ecosystem matches.
        return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
    }

    @Override
    public boolean supportsCreateIndexIfNotExists() {
        return false;
    }

    @Override
    public @NotNull String upsert(@NotNull EntityModel<?> model, @NotNull List<String> keyColumns) {
        List<String> keys = requireKeys(keyColumns);
        StringBuilder sql = new StringBuilder(160)
                .append("INSERT INTO ").append(table(model)).append(" (");
        appendColumnList(sql, model);
        sql.append(") VALUES (");
        appendPlaceholders(sql, model.columns().size());
        sql.append(')');
        appendConflict(sql, model, keys);
        return sql.toString();
    }

    /**
     * The {@code ON DUPLICATE KEY UPDATE} tail, in the form this engine wants.
     *
     * <p>MySQL 8.0.20 and up: a row alias. {@code VALUES(col)} still works and
     * logs a deprecation on every single write, which on a busy server is a log
     * file per day. The alias is what MariaDB cannot parse, and it is why
     * {@link MariaDBDialect} overrides this method rather than this class
     * covering both.
     */
    void appendConflict(@NotNull StringBuilder sql,
                        @NotNull EntityModel<?> model,
                        @NotNull List<String> keys) {
        List<ColumnModel> updatable = updatable(model, keys);
        if (updatable.isEmpty()) {
            // A table that is nothing but its key. MySQL has no DO NOTHING, and
            // an empty SET is a syntax error, so the key is assigned to itself:
            // the documented way to say "leave the row alone" here.
            sql.append(" ON DUPLICATE KEY UPDATE ")
                    .append(quote(keys.get(0))).append(" = ").append(quote(keys.get(0)));
            return;
        }
        sql.append(" AS new ON DUPLICATE KEY UPDATE ");
        for (int index = 0; index < updatable.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            String column = quote(identifier(updatable.get(index).name()));
            sql.append(column).append(" = new.").append(column);
        }
    }

    @Override
    public @NotNull String resequence(@NotNull EntityModel<?> model, long next) {
        // Not the standard ALTER COLUMN ... RESTART WITH, which MySQL and
        // MariaDB do not parse: the counter is a table attribute here, not a
        // column one. Both already advance it themselves when a row arrives
        // with an explicit key, so this is a restatement of where the engine
        // is — and it stays emitted so that the four engines end a repopulated
        // table in the same state rather than in whatever state the engine
        // somebody tested against happened to reach.
        return "ALTER TABLE " + table(model) + " AUTO_INCREMENT = " + next;
    }

    @Override
    public boolean isDuplicateIndex(@NotNull SQLException failure) {
        return hasCode(failure, DUPLICATE_INDEX)
                || (hasState(failure, "42000") && mentionsAll(failure, "duplicate key name"));
    }

    @Override
    public boolean isDuplicateColumn(@NotNull SQLException failure) {
        // 42S21 is the state the connector reports alongside 1060; both are
        // checked because a proxy in front of MySQL (ProxySQL, a router) can
        // pass one through and flatten the other.
        return hasCode(failure, DUPLICATE_COLUMN) || hasState(failure, "42S21");
    }
}
