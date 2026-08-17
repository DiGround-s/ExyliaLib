package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H2 2.2.224, embedded.
 *
 * <p>The engine a server gets when nobody configured one: a file next to the
 * plugin, no daemon, no credentials, no network. It is also the only engine the
 * library's own tests can execute, so anything the other three do differently
 * is asserted as a string rather than run.
 *
 * <h2>Upsert</h2>
 * {@code MERGE INTO t (...) KEY ("id") VALUES (...)}. H2 does support
 * {@code ON DUPLICATE KEY UPDATE} in its MySQL compatibility mode, but that
 * mode changes other things as well and would have to be switched on in the
 * URL; {@code MERGE ... KEY} is native, needs no mode, and takes exactly one
 * placeholder per column, which is what makes it a drop-in for the same bound
 * values every other dialect gets.
 *
 * <h2>The two URL flags that cannot both be set</h2>
 * {@code AUTO_SERVER=TRUE} and {@code DB_CLOSE_ON_EXIT=FALSE} are mutually
 * exclusive: H2 throws at connect time, before a single statement runs, so a
 * server configured that way does not start. Neither is used here.
 * {@code DB_CLOSE_DELAY=-1} does what was wanted from the second one — the
 * database survives the last connection closing rather than being torn down and
 * rebuilt between pool cycles — with none of that conflict.
 *
 * @see Dialect
 */
final class H2Dialect extends AnsiDialect {

    static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {
    }

    @Override
    public @NotNull String id() {
        return "h2";
    }

    @Override
    public @NotNull String driverClassName() {
        return "org.h2.Driver";
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull SqlSettings settings) {
        Map<String, String> parameters = new LinkedHashMap<>();
        // Without it, H2 drops the whole database the moment the pool closes its
        // last connection — which a pool does routinely when idle — and the next
        // connection gets an empty one. The alternative flag for this,
        // DB_CLOSE_ON_EXIT=FALSE, cannot be combined with AUTO_SERVER.
        parameters.put("DB_CLOSE_DELAY", "-1");
        // Nothing about identifier folding is set here — not DATABASE_TO_LOWER,
        // not DATABASE_TO_UPPER. H2 only accepts those on the connection that
        // creates the database and rejects them when an existing file is
        // reopened with a different value, so a flag added in a later release
        // would lock every server out of the data it already had. Quoting a
        // lower-case identifier achieves the same thing with no setting at all.

        Path file = settings.file();
        String target = file != null
                ? "file:" + file.toAbsolutePath()
                : "mem:" + (settings.database() != null ? settings.database() : "exylia");
        return "jdbc:h2:" + target + separated(parameters, settings);
    }

    /**
     * H2 separates URL parameters with {@code ;}, not {@code &}, and does not
     * introduce them with {@code ?}. A URL built the other way connects to a
     * database whose name contains the parameters.
     */
    private static @NotNull String separated(@NotNull Map<String, String> defaults,
                                             @NotNull SqlSettings settings) {
        Map<String, String> merged = new LinkedHashMap<>(defaults);
        merged.putAll(settings.properties());
        StringBuilder url = new StringBuilder(48);
        for (Map.Entry<String, String> parameter : merged.entrySet()) {
            url.append(';').append(parameter.getKey()).append('=').append(parameter.getValue());
        }
        return url.toString();
    }

    @Override
    public @NotNull PoolProfile poolProfile(@NotNull SqlSettings settings) {
        // An embedded connection is a few objects on the heap, not a socket: a
        // large pool buys nothing and a connection that expires buys less than
        // nothing, since recycling one can tear down and re-open the database
        // file. Lifetime and keepalive are therefore off, which is only safe
        // because there is no network in between to time anything out.
        int size = settings.poolSize() > 0 ? settings.poolSize() : 4;
        return new PoolProfile(size, Math.min(2, size), 0L, 0L);
    }

    @Override
    public @NotNull String quote(@NotNull String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @Override
    public @NotNull String unboundedTextType() {
        // H2 2.2.224 maps TEXT to an unlimited CHARACTER VARYING — verified
        // against the engine, not assumed: older H2 mapped it to CLOB, whose
        // values come back from getObject as a JdbcClob wrapper rather than a
        // String. Reads go through getString precisely so that either mapping
        // produces the stored text instead of "org.h2.jdbc.JdbcClob@1a2b".
        return "TEXT";
    }

    @Override
    public boolean supportsCreateIndexIfNotExists() {
        return true;
    }

    @Override
    public @NotNull String upsert(@NotNull EntityModel<?> model, @NotNull List<String> keyColumns) {
        List<String> keys = requireKeys(keyColumns);
        StringBuilder sql = new StringBuilder(128)
                .append("MERGE INTO ").append(table(model)).append(" (");
        appendColumnList(sql, model);
        sql.append(") KEY (");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(quote(keys.get(index)));
        }
        sql.append(") VALUES (");
        appendPlaceholders(sql, model.columns().size());
        return sql.append(')').toString();
    }

    @Override
    public boolean isDuplicateIndex(@NotNull SQLException failure) {
        // 42111 INDEX_ALREADY_EXISTS_1, 42121 DUPLICATE_COLUMN_NAME_1's index
        // sibling. Both are still recognised even though H2 parses
        // IF NOT EXISTS: a table created by an older version of a plugin can
        // carry the index under a different name, and the raced-start case
        // (two servers on one file) reports it here rather than at parse time.
        return hasCode(failure, 42111) || hasState(failure, "42S11")
                || mentionsAll(failure, "index", "already exists");
    }

    @Override
    public boolean isDuplicateColumn(@NotNull SQLException failure) {
        return hasCode(failure, 42121) || hasState(failure, "42S21")
                || mentionsAll(failure, "column", "already exists");
    }
}
