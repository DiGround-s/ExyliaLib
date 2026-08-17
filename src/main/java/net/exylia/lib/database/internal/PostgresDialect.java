package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL, through the {@code org.postgresql} driver.
 *
 * <p>The strictest of the four, which mostly means it fails where the others
 * quietly do something else.
 *
 * <h2>Upsert</h2>
 * {@code INSERT ... ON CONFLICT ("id") DO UPDATE SET col = EXCLUDED.col}. The
 * conflict target is mandatory: {@code ON CONFLICT DO UPDATE} without one is a
 * hard error, not a default to the primary key. This is why
 * {@link Dialect#upsert} takes the key columns as an argument rather than
 * reading them off the model — Postgres has to be told, so every dialect is
 * told, and the four statements stay built from the same inputs.
 *
 * <h2>Types</h2>
 * {@code TINYINT} does not exist and a {@code byte} column widens to
 * {@code SMALLINT}. {@code FLOAT} is 8 bytes here and 4 on MySQL, which is why
 * {@link AnsiDialect} emits {@code REAL} and {@code DOUBLE PRECISION} and never
 * the ambiguous word.
 *
 * <p>The {@code uuid} type is native here and is <em>not</em> used. A native
 * {@code uuid} column rejects {@link java.sql.PreparedStatement#setString}
 * outright, so a repository that writes UUIDs as text — which it must, because
 * MySQL has no such type — starts throwing on every write the day a server
 * migrates. {@code VARCHAR(36)} is the one representation all four share.
 *
 * <h2>Pagination</h2>
 * Postgres rejects MySQL's {@code LIMIT ?, ?} entirely.
 * {@code LIMIT ? OFFSET ?} is the only form all four parse, and it is what
 * {@link Dialect#page} emits everywhere.
 *
 * @see Dialect
 */
final class PostgresDialect extends AnsiDialect {

    static final PostgresDialect INSTANCE = new PostgresDialect();

    /** SQLState 42P07: duplicate table, which covers a duplicate index here. */
    private static final String DUPLICATE_OBJECT = "42P07";

    /** SQLState 42701: duplicate column. */
    private static final String DUPLICATE_COLUMN = "42701";

    private PostgresDialect() {
    }

    @Override
    public @NotNull String id() {
        return "postgres";
    }

    @Override
    public @NotNull String driverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    public @NotNull String jdbcUrl(@NotNull SqlSettings settings) {
        Map<String, String> parameters = new LinkedHashMap<>();
        // reWriteBatchedInserts, with a capital W in the middle. The driver
        // silently ignores a parameter it does not recognise, so a lower-case
        // "rewrite" spelling costs the whole batching win and produces no
        // warning, no error, and no way to tell from the outside.
        parameters.put("reWriteBatchedInserts", "true");
        // ASCII names only, always: an operator who names a database with an
        // accent should get a working connection rather than mojibake.
        parameters.put("ApplicationName", "ExyliaLib");
        return "jdbc:postgresql://" + settings.host() + ":" + settings.portOr(5432)
                + "/" + settings.database() + query(parameters, settings);
    }

    @Override
    public @NotNull PoolProfile poolProfile(@NotNull SqlSettings settings) {
        // Postgres backends are processes, not threads: an oversized pool costs
        // real memory on the database host, not just a socket. Modest by
        // default and configurable upward by whoever measured a reason.
        int size = settings.poolSize() > 0 ? settings.poolSize() : 8;
        return new PoolProfile(size, Math.min(2, size), 1_800_000L, 300_000L);
    }

    @Override
    public @NotNull String quote(@NotNull String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @Override
    public @NotNull String tinyIntType() {
        // No TINYINT. SMALLINT is the narrowest integer here, and a byte column
        // read back through Coercions narrows again on the Java side.
        return "SMALLINT";
    }

    @Override
    public @NotNull String unboundedTextType() {
        // Genuinely unbounded, and no slower than VARCHAR: Postgres stores both
        // the same way and large values go to TOAST either way.
        return "TEXT";
    }

    @Override
    public boolean supportsCreateIndexIfNotExists() {
        return true;
    }

    @Override
    public @NotNull String upsert(@NotNull EntityModel<?> model, @NotNull List<String> keyColumns) {
        List<String> keys = requireKeys(keyColumns);
        StringBuilder sql = new StringBuilder(176)
                .append("INSERT INTO ").append(table(model)).append(" (");
        appendColumnList(sql, model);
        sql.append(") VALUES (");
        appendPlaceholders(sql, model.columns().size());
        sql.append(") ON CONFLICT (");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(quote(keys.get(index)));
        }
        sql.append(')');

        List<ColumnModel> updatable = updatable(model, keys);
        if (updatable.isEmpty()) {
            // A table that is nothing but its key. Postgres has DO NOTHING for
            // exactly this, so unlike MySQL there is no need to assign a column
            // to itself.
            return sql.append(" DO NOTHING").toString();
        }
        sql.append(" DO UPDATE SET ");
        for (int index = 0; index < updatable.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            String column = quote(identifier(updatable.get(index).name()));
            sql.append(column).append(" = EXCLUDED.").append(column);
        }
        return sql.toString();
    }

    @Override
    public boolean isDuplicateIndex(@NotNull SQLException failure) {
        // An index is a relation here, so a clash reads
        // "relation \"idx_x\" already exists" and carries 42P07; 42710 is the
        // constraint-level sibling a UNIQUE index can raise instead.
        return hasState(failure, DUPLICATE_OBJECT, "42710")
                || mentionsAll(failure, "relation", "already exists");
    }

    @Override
    public boolean isDuplicateColumn(@NotNull SQLException failure) {
        return hasState(failure, DUPLICATE_COLUMN)
                || mentionsAll(failure, "column", "already exists");
    }
}
