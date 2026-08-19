package net.exylia.lib.database.internal;

import net.exylia.lib.database.Column;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The statements the four engines actually write the same way.
 *
 * <p>{@code SELECT}, {@code INSERT}, {@code DELETE} and {@code COUNT} are
 * genuinely portable once identifiers are quoted and values are bound, so they
 * are written once here. Everything a live probe found to differ — upsert,
 * types, index guards, error codes, URLs — stays abstract and is answered by
 * each engine's own subclass.
 *
 * <p>This is the boundary between "shared because it is the same" and "shared
 * because nobody checked". A subclass overriding something here is a signal
 * that the assumption was wrong for that engine, which is exactly how
 * {@code TINYINT} on Postgres and {@code BOOLEAN} on MySQL got found.
 *
 * @see Dialect
 */
abstract class AnsiDialect implements Dialect {

    /**
     * The widest a text column can be and still carry a usable index.
     *
     * <p>InnoDB's index-key limit is 3072 bytes on the {@code DYNAMIC} row
     * format, and {@code utf8mb4} costs 4 bytes per character: 3072 / 4 = 768.
     * MySQL refuses to create the index above it. MariaDB accepts the statement
     * and quietly builds a 768-character <em>prefix</em> index instead, which
     * still answers lookups and no longer enforces {@code UNIQUE} on the full
     * value — two rows differing only after character 768 would both be
     * accepted, and nothing anywhere would say so.
     *
     * <p>H2 and Postgres have no such limit, but the number applies to all four
     * anyway: a schema that only works on the engine it was developed against
     * is the failure this whole layer exists to prevent.
     */
    static final int MAX_INDEXED_TEXT = 768;

    /** How wide a canonical UUID string is, exactly. */
    static final int UUID_TEXT_LENGTH = 36;

    // ------------------------------------------------------------------ types

    @Override
    public @NotNull String columnType(@NotNull ColumnModel column) {
        Class<?> stored = column.storedType();
        if (stored == String.class) {
            return textType(column);
        }
        if (stored == int.class || stored == Integer.class) {
            return "INTEGER";
        }
        if (stored == long.class || stored == Long.class) {
            return "BIGINT";
        }
        if (stored == short.class || stored == Short.class) {
            return "SMALLINT";
        }
        if (stored == byte.class || stored == Byte.class) {
            return tinyIntType();
        }
        if (stored == double.class || stored == Double.class) {
            // Never FLOAT: it is 8 bytes on H2 and Postgres and 4 on MySQL, so a
            // schema written with FLOAT changes precision the day a server moves
            // between engines, and the change is invisible until somebody
            // compares a stored ratio with the one that was written.
            return "DOUBLE PRECISION";
        }
        if (stored == float.class || stored == Float.class) {
            return "REAL";
        }
        if (stored == boolean.class || stored == Boolean.class) {
            return booleanType();
        }
        if (stored == BigDecimal.class) {
            // A BigDecimal column exists because something is money, and money
            // is the one thing that must not arrive back rounded. 38 digits is
            // the largest precision all four engines accept, and 10 decimals
            // covers per-unit prices without giving up integer range.
            return "DECIMAL(38,10)";
        }
        throw new IllegalArgumentException("No SQL type for column '" + column.name()
                + "' stored as " + stored.getName());
    }

    /**
     * The type for a text column, bounded or not.
     *
     * <p>A {@code UUID} is stored as text of exactly the width a canonical UUID
     * has, on every engine, whatever the annotation asked for. Postgres has a
     * native {@code uuid} type and it is a trap: the column rejects
     * {@link java.sql.PreparedStatement#setString}, so the day a server moves
     * from MySQL — which has no such type at all — every write starts throwing.
     * Text is the only representation the four engines share.
     */
    private @NotNull String textType(@NotNull ColumnModel column) {
        if (column.javaType() == UUID.class) {
            return "VARCHAR(" + UUID_TEXT_LENGTH + ")";
        }
        int length = column.length();
        if (length == Column.UNBOUNDED) {
            return unboundedTextType();
        }
        return "VARCHAR(" + Math.max(1, length) + ")";
    }

    /** The type an 8-bit integer becomes. Postgres has none and widens it. */
    @NotNull String tinyIntType() {
        return "TINYINT";
    }

    /**
     * How a column declares that the engine fills it.
     *
     * <p>Appended after the type, which is what H2, MySQL and MariaDB all
     * expect. Postgres spells the whole thing as one type instead and overrides
     * both this and {@link #columnType} together.
     */
    @NotNull String autoIncrement() {
        return " AUTO_INCREMENT";
    }

    /** The type a boolean becomes. MySQL and MariaDB have no real one. */
    @NotNull String booleanType() {
        return "BOOLEAN";
    }

    // -------------------------------------------------------------------- DDL

    @Override
    public @NotNull String createTable(@NotNull EntityModel<?> model) {
        StringBuilder sql = new StringBuilder(128)
                .append("CREATE TABLE IF NOT EXISTS ")
                .append(table(model))
                .append(" (");
        List<ColumnModel> columns = model.columns();
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            ColumnModel column = columns.get(index);
            sql.append(quote(identifier(column.name()))).append(' ').append(columnType(column));
            if (!column.nullable()) {
                sql.append(" NOT NULL");
            }
            if (column.generated()) {
                sql.append(autoIncrement());
            }
        }
        sql.append(", PRIMARY KEY (")
                .append(quote(identifier(model.id().name())))
                .append("))")
                .append(tableSuffix());
        return sql.toString();
    }

    @Override
    public @NotNull String createIndex(@NotNull String table, @NotNull IndexModel index) {
        StringBuilder sql = new StringBuilder(112).append("CREATE ");
        if (index.unique()) {
            sql.append("UNIQUE ");
        }
        sql.append("INDEX ");
        if (supportsCreateIndexIfNotExists()) {
            sql.append("IF NOT EXISTS ");
        }
        sql.append(quote(indexName(index)))
                .append(" ON ").append(quote(identifier(table)))
                .append(" (");
        List<IndexModel.Part> parts = index.parts();
        for (int position = 0; position < parts.size(); position++) {
            if (position > 0) {
                sql.append(", ");
            }
            IndexModel.Part part = parts.get(position);
            // ASC is written out rather than left implicit. It is the default on
            // all four engines, so it changes nothing about the index — but a
            // statement that says which way every column is sorted is a
            // statement somebody reading a schema dump can check against the
            // query it exists for, and that is the mistake this module is here
            // to catch.
            sql.append(quote(identifier(part.column())))
                    .append(part.descending() ? " DESC" : " ASC");
        }
        return sql.append(')').toString();
    }

    @Override
    public @NotNull String addColumn(@NotNull String table, @NotNull ColumnModel column) {
        // Deliberately no NOT NULL, whatever the model says: see Dialect#addColumn.
        return "ALTER TABLE " + quote(identifier(table))
                + " ADD COLUMN " + quote(identifier(column.name()))
                + " " + columnType(column);
    }

    // -------------------------------------------------------------------- DML

    @Override
    public @NotNull String insert(@NotNull EntityModel<?> model) {
        StringBuilder sql = new StringBuilder(96)
                .append("INSERT INTO ").append(table(model)).append(" (");
        appendColumnList(sql, model);
        sql.append(") VALUES (");
        appendPlaceholders(sql, model.columns().size());
        return sql.append(')').toString();
    }

    @Override
    public @NotNull String insertGenerated(@NotNull EntityModel<?> model) {
        List<ColumnModel> columns = model.insertColumns();
        if (columns.isEmpty()) {
            // A table that is nothing but a generated key. Rare, but legal:
            // an id handed out to something that only needs to be counted.
            return "INSERT INTO " + table(model) + " VALUES (" + defaultKeyword() + ')';
        }
        StringBuilder sql = new StringBuilder(96)
                .append("INSERT INTO ").append(table(model)).append(" (");
        appendColumnList(sql, columns);
        sql.append(") VALUES (");
        appendPlaceholders(sql, columns.size());
        return sql.append(')').toString();
    }

    /** How an engine spells "use the value you would have picked". */
    @NotNull String defaultKeyword() {
        return "DEFAULT";
    }

    @Override
    public @NotNull String select(@NotNull EntityModel<?> model,
                                  @NotNull List<String> whereColumns,
                                  @NotNull List<Sort> order,
                                  int limit,
                                  int offset) {
        StringBuilder sql = new StringBuilder(128).append("SELECT ");
        appendColumnList(sql, model);
        sql.append(" FROM ").append(table(model));
        appendWhere(sql, whereColumns);
        if (!order.isEmpty()) {
            sql.append(" ORDER BY ");
            for (int index = 0; index < order.size(); index++) {
                if (index > 0) {
                    sql.append(", ");
                }
                Sort sort = order.get(index);
                sql.append(quote(identifier(sort.column())));
                if (sort.descending()) {
                    sql.append(" DESC");
                }
            }
        }
        return sql.append(page(limit, offset)).toString();
    }

    @Override
    public @NotNull String scan(@NotNull EntityModel<?> model, boolean after) {
        String key = quote(identifier(model.id().name()));
        StringBuilder sql = new StringBuilder(128).append("SELECT ");
        appendColumnList(sql, model);
        sql.append(" FROM ").append(table(model));
        if (after) {
            // Strictly greater than. A >= would re-read the row the previous
            // batch ended on, so every row after the first batch is handed over
            // twice and a table whose size is a multiple of the batch never
            // terminates.
            sql.append(" WHERE ").append(key).append(" > ?");
        }
        // A bare LIMIT, not page(): the offset half of that clause is what this
        // whole statement exists to avoid, and binding a constant zero on every
        // batch would be a placeholder that only ever means "not this".
        // All four engines parse LIMIT ? on its own; the one that did not would
        // override this method, as MySQL overrides the index guard.
        return sql.append(" ORDER BY ").append(key).append(" LIMIT ?").toString();
    }

    @Override
    public @NotNull String maxKey(@NotNull EntityModel<?> model) {
        return "SELECT MAX(" + quote(identifier(model.id().name())) + ") FROM " + table(model);
    }

    @Override
    public @NotNull String resequence(@NotNull EntityModel<?> model, long next) {
        // The standard spelling, which H2 and Postgres both take for an
        // identity column. MySQL and MariaDB have their own and override this.
        return "ALTER TABLE " + table(model)
                + " ALTER COLUMN " + quote(identifier(model.id().name()))
                + " RESTART WITH " + next;
    }

    @Override
    public @NotNull String delete(@NotNull EntityModel<?> model, @NotNull List<String> whereColumns) {
        if (whereColumns.isEmpty()) {
            // A DELETE with no filter empties the table, and nothing in this
            // library ever means to. Somebody clearing a table on purpose can
            // say so in their own statement, where it is visible in review.
            throw new IllegalArgumentException("DELETE FROM " + model.table()
                    + " with no condition would empty the table. Name the columns to match on.");
        }
        StringBuilder sql = new StringBuilder(64).append("DELETE FROM ").append(table(model));
        appendWhere(sql, whereColumns);
        return sql.toString();
    }

    @Override
    public @NotNull String count(@NotNull EntityModel<?> model, @NotNull List<String> whereColumns) {
        StringBuilder sql = new StringBuilder(64)
                .append("SELECT COUNT(*) FROM ").append(table(model));
        appendWhere(sql, whereColumns);
        return sql.toString();
    }

    // ------------------------------------------------------------- validation

    @Override
    public @NotNull List<String> validate(@NotNull EntityModel<?> model) {
        List<String> problems = new ArrayList<>(0);
        Map<String, String> folded = new LinkedHashMap<>();
        // Every column any index covers, not just the ones carrying @Indexed. A
        // column pulled into an index only by a composite @Index on the record
        // is indexed just as much, and it is the wide text column in the middle
        // of a composite index that MariaDB silently shortens.
        Set<String> indexed = new HashSet<>();
        for (IndexModel index : model.indexes()) {
            indexed.addAll(index.columns());
        }
        for (ColumnModel column : model.columns()) {
            String name = identifier(column.name());
            String clash = folded.putIfAbsent(name, column.name());
            if (clash != null && !clash.equals(column.name())) {
                // Two columns that differ only in case are two columns on H2 and
                // Postgres and one column on MySQL, where the second write
                // overwrites the first. Since every identifier is folded before
                // it is quoted, they are one column here too, and that has to be
                // said out loud rather than discovered in the data.
                problems.add("columns '" + clash + "' and '" + column.name()
                        + "' differ only in case, and identifiers are stored lower case:"
                        + " they would be the same column and one would overwrite the other");
            }
            problems.addAll(validateColumn(model, column, indexed.contains(column.name())));
        }
        problems.addAll(validateIndexKeyWidths(model));
        return List.copyOf(problems);
    }

    /**
     * Composite indexes whose columns are together too wide to be a key.
     *
     * <p>InnoDB's limit is on the whole index key, not on each column: 3072
     * bytes for all of them added up. Two 768-character text columns each pass
     * the per-column check and together overrun it, at which point MySQL refuses
     * the index and MariaDB builds a prefix of it. Checked separately from the
     * per-column rule because it is a property of the index, and a per-column
     * loop structurally cannot see it.
     */
    private @NotNull List<String> validateIndexKeyWidths(@NotNull EntityModel<?> model) {
        List<String> problems = new ArrayList<>(0);
        for (IndexModel index : model.indexes()) {
            if (!index.composite()) {
                // A single-column index is already covered per column, with a
                // message naming the column rather than the index.
                continue;
            }
            int characters = 0;
            for (String name : index.columns()) {
                ColumnModel column = model.column(name);
                if (column != null && column.storedType() == String.class) {
                    characters += column.javaType() == UUID.class
                            ? UUID_TEXT_LENGTH
                            : Math.max(1, column.length());
                }
            }
            if (characters > MAX_INDEXED_TEXT) {
                problems.add("the index " + index.name() + " over " + index.columns()
                        + " has " + characters + " characters of text in its key."
                        + " InnoDB limits a whole index key to " + MAX_INDEXED_TEXT
                        + " characters of utf8mb4, counting every column together, so MySQL would"
                        + " refuse this index and MariaDB would silently build a prefix of it."
                        + " Narrow the text columns, or index fewer of them.");
            }
        }
        return problems;
    }

    private @NotNull List<String> validateColumn(@NotNull EntityModel<?> model,
                                                 @NotNull ColumnModel column,
                                                 boolean indexed) {
        if (column.storedType() != String.class || column.javaType() == UUID.class) {
            return List.of();
        }
        boolean needsIndex = column.id() || indexed;
        if (!needsIndex) {
            return List.of();
        }
        String where = model.table() + "." + column.name();
        if (column.length() == Column.UNBOUNDED) {
            return List.of(where + " is indexed and unbounded. "
                    + id() + " cannot index a " + unboundedTextType()
                    + " column, so the index would either be refused or silently truncated."
                    + " Give the column a length of at most " + maxIndexedTextLength() + ".");
        }
        if (column.length() > maxIndexedTextLength()) {
            return List.of(where + " is indexed and " + column.length() + " characters wide."
                    + " MySQL refuses an index above " + maxIndexedTextLength()
                    + " characters and MariaDB silently builds a prefix index instead,"
                    + (column.unique() || column.id()
                        ? " which stops enforcing uniqueness on the full value."
                        : " which is not the index that was asked for.")
                    + " Narrow the column to " + maxIndexedTextLength() + " characters or fewer.");
        }
        return List.of();
    }

    @Override
    public int maxIndexedTextLength() {
        return MAX_INDEXED_TEXT;
    }

    // -------------------------------------------------------------- utilities

    /** The table of a model, folded and quoted. */
    final @NotNull String table(@NotNull EntityModel<?> model) {
        return quote(identifier(model.table()));
    }

    /** Every column of a model, folded, quoted and comma-separated. */
    final void appendColumnList(@NotNull StringBuilder sql, @NotNull EntityModel<?> model) {
        appendColumnList(sql, model.columns());
    }

    /** A given list of columns, folded, quoted and comma-separated. */
    final void appendColumnList(@NotNull StringBuilder sql, @NotNull List<ColumnModel> columns) {
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(quote(identifier(columns.get(index).name())));
        }
    }

    /** {@code ?, ?, ?} — never a value, on any path. */
    static void appendPlaceholders(@NotNull StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
    }

    private void appendWhere(@NotNull StringBuilder sql, @NotNull List<String> whereColumns) {
        if (whereColumns.isEmpty()) {
            return;
        }
        sql.append(" WHERE ");
        for (int index = 0; index < whereColumns.size(); index++) {
            if (index > 0) {
                sql.append(" AND ");
            }
            sql.append(quote(identifier(whereColumns.get(index)))).append(" = ?");
        }
    }

    /**
     * The columns an upsert writes when a row already exists.
     *
     * <p>The key columns are left out: setting a key to the value it was
     * matched on is work for nothing, and on MySQL it makes the statement look
     * like it modified a row when it did not.
     *
     * @param model      the record model
     * @param keyColumns the conflict columns, already folded
     * @return the remaining columns, possibly empty
     */
    static @NotNull List<ColumnModel> updatable(@NotNull EntityModel<?> model,
                                                @NotNull List<String> keyColumns) {
        List<ColumnModel> updatable = new ArrayList<>(model.columns().size());
        for (ColumnModel column : model.columns()) {
            if (!containsIgnoreCase(keyColumns, column.name())) {
                updatable.add(column);
            }
        }
        return updatable;
    }

    /**
     * Checks the key list a caller passed and folds it.
     *
     * @param keyColumns the conflict columns
     * @return the same names, folded
     * @throws IllegalArgumentException if the list is empty
     */
    final @NotNull List<String> requireKeys(@NotNull List<String> keyColumns) {
        if (keyColumns.isEmpty()) {
            throw new IllegalArgumentException("An upsert needs the columns that decide a conflict."
                    + " Postgres treats a missing conflict target as an error and H2 would merge on"
                    + " whatever key the table happens to have, so there is no safe default.");
        }
        List<String> folded = new ArrayList<>(keyColumns.size());
        for (String key : keyColumns) {
            folded.add(identifier(key));
        }
        return folded;
    }

    private static boolean containsIgnoreCase(@NotNull List<String> names, @NotNull String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A JDBC URL query string from the library's parameters and the operator's.
     *
     * <p>The operator's come last so they win. A server behind a proxy that
     * needs a different {@code sslMode} should not need a library release.
     *
     * @param defaults  what this dialect insists on
     * @param settings  what the operator configured
     * @return the query string, starting with {@code ?}, or empty
     */
    static @NotNull String query(@NotNull Map<String, String> defaults, @NotNull SqlSettings settings) {
        Map<String, String> merged = new LinkedHashMap<>(defaults);
        merged.putAll(settings.properties());
        if (merged.isEmpty()) {
            return "";
        }
        StringBuilder query = new StringBuilder(64);
        for (Map.Entry<String, String> parameter : merged.entrySet()) {
            query.append(query.isEmpty() ? '?' : '&')
                    .append(parameter.getKey()).append('=').append(parameter.getValue());
        }
        return query.toString();
    }

    /** Whether a driver failure carries one of the given vendor codes. */
    static boolean hasCode(@NotNull java.sql.SQLException failure, int... codes) {
        for (java.sql.SQLException current = failure; current != null; current = current.getNextException()) {
            for (int code : codes) {
                if (current.getErrorCode() == code) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a driver failure carries one of the given SQL states. */
    static boolean hasState(@NotNull java.sql.SQLException failure, @NotNull String... states) {
        for (java.sql.SQLException current = failure; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state == null) {
                continue;
            }
            for (String candidate : states) {
                if (state.equalsIgnoreCase(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a driver's message contains every one of these fragments.
     *
     * <p>A last resort, and never the only check: a code is checked first and
     * this only widens it. Every fragment must be present, because any single
     * one of them is far too common on its own — matching a message that merely
     * mentions "index" would swallow a genuinely broken {@code CREATE INDEX}
     * and leave a table without the index it was promised.
     *
     * <p>Matched in lower case against the English text; JDBC drivers do not
     * localise their own errors.
     */
    static boolean mentionsAll(@NotNull java.sql.SQLException failure, @NotNull String... fragments) {
        for (java.sql.SQLException current = failure; current != null; current = current.getNextException()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String fragment : fragments) {
                if (!lower.contains(fragment)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }
}
