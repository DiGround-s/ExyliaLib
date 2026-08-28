package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MariaDB, through {@code mariadb-java-client}.
 *
 * <p>A MySQL fork that has not been a drop-in replacement for some years. It
 * shares almost everything with {@link MySQLDialect} — backtick quoting,
 * {@code TINYINT(1)} for booleans, {@code LONGTEXT}, the InnoDB table suffix —
 * and differs on exactly the three things below.
 *
 * <h2>The row alias</h2>
 * MariaDB does not implement MySQL 8.0.20's {@code INSERT ... AS new} alias:
 * the statement fails to <em>parse</em>, so it is not a warning, a fallback, or
 * a portability nicety. The constructor therefore turns the alias off, and
 * {@link MySQLDialect#appendConflict} emits the older {@code VALUES(col)}
 * form, which MariaDB implements and does not deprecate.
 *
 * <p>An operator who configures {@code mysql} for a MariaDB server — which is
 * what most of them write, and which connector-j connects to happily — never
 * reaches this class by name. {@link SqlBackend} asks the server what it is on
 * the first connection and switches to this dialect when the answer says
 * MariaDB, because the alternative is a syntax error on every write.
 *
 * <h2>{@code CREATE INDEX IF NOT EXISTS}</h2>
 * MariaDB has supported it since 10.0, unlike MySQL. It is used, because a
 * metadata round trip per index per start buys nothing here.
 *
 * <h2>The 768-character index limit still applies</h2>
 * And it is worse here than on MySQL: MySQL refuses an index above the InnoDB
 * key limit, while MariaDB accepts the statement and silently builds a prefix
 * index over the first 768 characters. A column declared {@code unique} then
 * stops enforcing uniqueness on the full value, and nothing anywhere says so.
 * See {@link AnsiDialect#MAX_INDEXED_TEXT} and the validation that reports it.
 *
 * @see Dialect
 */
final class MariaDBDialect extends MySQLDialect {

    static final MariaDBDialect INSTANCE = new MariaDBDialect();

    private MariaDBDialect() {
        // Never the row alias: it is a parse error on this engine, at every
        // version, on every single write.
        super(false);
    }

    @Override
    public @NotNull String id() {
        return "mariadb";
    }

    @Override
    public @NotNull String driverClassName() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull SqlSettings settings) {
        return "jdbc:mariadb://" + settings.host() + ":" + settings.portOr(3306)
                + "/" + settings.database() + query(urlParameters(), settings);
    }

    /**
     * The parameters this driver takes, which are not connector-j's.
     *
     * <p>{@code sslMode} and {@code allowPublicKeyRetrieval} are left out on
     * purpose: they are connector-j settings for a problem MariaDB does not
     * have — {@code caching_sha2_password} is MySQL's default authentication
     * plugin, not MariaDB's — and this driver rejects some unknown parameters
     * outright rather than ignoring them, which turns a copied config into a
     * server that will not start.
     *
     * <p>{@code rewriteBatchedStatements} means the same here as there and is
     * worth the same order of magnitude on a batch.
     */
    @Override
    @NotNull Map<String, String> urlParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("rewriteBatchedStatements", "true");
        parameters.put("characterEncoding", "UTF-8");
        return parameters;
    }

    @Override
    public boolean supportsCreateIndexIfNotExists() {
        return true;
    }

    @Override
    public boolean isDuplicateIndex(@NotNull SQLException failure) {
        // The fork kept MySQL's error numbers, so 1061 still applies, but this
        // driver has reported it with a bare 42000 state in some versions.
        return super.isDuplicateIndex(failure) || mentionsAll(failure, "duplicate key name");
    }
}
