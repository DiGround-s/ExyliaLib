package net.exylia.lib.database.internal;

import net.exylia.lib.database.Column;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Bringing a live table up to what a record says it should be.
 *
 * <p>Runs on every start, so every step has to be idempotent — and idempotent
 * on four engines that disagree about how to spell "if it is not already
 * there". Three of them parse {@code IF NOT EXISTS} on an index and MySQL
 * treats it as a syntax error, so the guard is a metadata lookup first and a
 * swallowed vendor code second.
 *
 * <h2>What it will and will not do</h2>
 * It creates a table, adds a column a record has gained, creates an index,
 * renames a table or column that is there under the engine's own folding into
 * the case this library addresses it by, and widens a text column a record now
 * declares wider than the table stores it. It never drops, narrows or otherwise
 * retypes anything, and it never touches a column the record does not declare — in
 * either direction. A schema tool that removes a column because a record
 * stopped declaring it is a schema tool that deletes a live server's data the
 * first time somebody deploys an old jar, and one that renames a column it does
 * not own breaks the plugin it was never told about.
 *
 * <h2>Threads</h2>
 * Called from the background pool, once per repository at enable. Not
 * thread-safe against itself for the same table, and does not need to be: two
 * servers racing on one database both end up with the table, because every
 * statement here tolerates having lost the race.
 *
 * @see Dialect
 */
final class SqlSchema {

    private final Dialect dialect;

    SqlSchema(@NotNull Dialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Creates the table if it is missing, adds the columns it lacks, and
     * creates the indexes the model asks for.
     *
     * @param connection an open connection, owned by the caller
     * @param model      the record model
     * @return what was actually changed, for the caller to report
     * @throws SQLException if a statement failed for a reason other than
     *                      "already there"
     */
    @NotNull SchemaReport ensure(@NotNull Connection connection, @NotNull EntityModel<?> model)
            throws SQLException {
        String table = dialect.identifier(model.table());
        boolean created = false;
        String legacy = differentlyCasedTable(connection, table);
        if (legacy != null) {
            // The table is there under the engine's own folding, because
            // something created it unquoted — an older ExyliaCommons plugin, or
            // a hand-written CREATE. Every statement this library builds quotes
            // the lower-case name, so without this the table is found, creation
            // is skipped, and then every single read and write fails with
            // "table not found (candidates are: THE_ONE_RIGHT_THERE)".
            // Renaming once is what makes the existing rows reachable; the
            // alternative, addressing it in its own case forever, spreads the
            // engine's folding rules through every statement the library emits.
            execute(connection, dialect.renameTable(legacy, table));
            // The columns were folded by the same engine on the same day, so a
            // table found this way always has them in that case too. Renaming
            // the table alone moves the failure one level down, from "table not
            // found" to "column not found", which is the same outage. The
            // column pass below is what closes that, and it runs for every
            // pre-existing table rather than only for this branch — see there.
        }
        if (!tableExists(connection, table)) {
            execute(connection, dialect.createTable(model));
            created = true;
        }
        // Only asked of a table that was already there. A table this method
        // just created has every column the model declares by construction, and
        // asking anyway is a metadata round trip per table per start for an
        // answer that is known.
        List<String> addedColumns = new ArrayList<>(0);
        List<String> renamedColumns = new ArrayList<>(0);
        List<String> relaxedColumns = new ArrayList<>(0);
        List<String> widenedColumns = new ArrayList<>(0);
        List<String> narrowColumns = new ArrayList<>(0);
        if (!created) {
            reconcileColumns(connection, model, table, addedColumns, renamedColumns,
                    relaxedColumns, widenedColumns, narrowColumns);
        }
        List<String> createdIndexes = new ArrayList<>(0);
        List<String> blockedIndexes = new ArrayList<>(0);
        createIndexes(connection, model, table, created, createdIndexes, blockedIndexes);
        return new SchemaReport(table, created, addedColumns, renamedColumns, relaxedColumns,
                widenedColumns, createdIndexes, blockedIndexes, narrowColumns);
    }

    // ----------------------------------------------------------- inspection

    /**
     * Whether a table exists, asked in a way that survives all four engines.
     *
     * <p>Two traps live in this one call:
     *
     * <ul>
     *   <li><b>Case.</b> {@code getTables} matches the identifier as
     *       <em>stored</em>, and the engines fold differently — H2 upper-cases
     *       an unquoted name, Postgres lower-cases it, MySQL depends on
     *       {@code lower_case_table_names}. Since this library always quotes a
     *       lower-case name, the stored form is lower case everywhere; the
     *       upper-case form is still tried, because a table created by hand or
     *       by an older ExyliaCommons plugin was not created this way. Getting
     *       it wrong means the table looks missing and {@code CREATE TABLE}
     *       runs against a table that is already full of rows.</li>
     *   <li><b>{@code _} is a LIKE wildcard.</b> The pattern arguments of
     *       {@code getTables} and {@code getColumns} are patterns, not names, so
     *       {@code player_data} also matches {@code playerXdata}. Escaping is
     *       possible but the escape character is per-driver
     *       ({@code getSearchStringEscape}), so the result is filtered by exact
     *       name instead — one comparison, and no driver quirk to get wrong.</li>
     * </ul>
     */
    boolean tableExists(@NotNull Connection connection, @NotNull String table) throws SQLException {
        return storedNameOf(connection, table, true) != null;
    }

    /**
     * The name a same-named table is actually stored under, when that differs
     * from the one this library addresses it by.
     *
     * <p>{@code null} when there is no such table, or when it is stored exactly
     * as expected — the ordinary case, which must cost nothing extra.
     */
    private @Nullable String differentlyCasedTable(@NotNull Connection connection,
                                                   @NotNull String table) throws SQLException {
        String stored = storedNameOf(connection, table, false);
        return stored != null && !stored.equals(table) ? stored : null;
    }

    /**
     * The {@code TABLE_NAME} the driver reports for a table, in its own case.
     *
     * <p>Compared case-insensitively on purpose: the engines fold differently,
     * and the point of the lookup is to find the table whatever case it landed
     * in. {@code exactOnly} narrows that to the form this library writes, for
     * the caller that is deciding whether to run {@code CREATE TABLE}.
     */
    private @Nullable String storedNameOf(@NotNull Connection connection, @NotNull String table,
                                          boolean exactOnly) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : metadataForms(table)) {
            try (ResultSet tables = metadata.getTables(connection.getCatalog(), schemaOf(connection),
                    candidate, new String[]{"TABLE"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (!table.equalsIgnoreCase(name)) {
                        continue;
                    }
                    if (exactOnly && !table.equals(name)) {
                        continue;
                    }
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * Every column of a live table, keyed by its folded name and valued by the
     * name the driver reports.
     *
     * <p>Keyed folded and valued as stored on purpose: the caller needs both
     * halves of the answer from one read — the key says whether a column the
     * record declares is there at all, and the value says whether it is spelled
     * the way this library addresses it. A lookup that only kept the folded name
     * cannot tell "already correct" from "there under the engine's own folding",
     * and that is the difference between a working table and an outage.
     *
     * @param connection an open connection
     * @param table      the folded table name
     * @return the columns, possibly empty when the table does not exist or when
     *         the driver scoped the query differently than expected
     */
    private @NotNull Map<String, Stored> storedColumns(@NotNull Connection connection,
                                                       @NotNull String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, Stored> found = new LinkedHashMap<>();
        for (String candidate : metadataForms(table)) {
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), schemaOf(connection),
                    candidate, null)) {
                while (columns.next()) {
                    // The same post-filter as tableExists, for the same reason:
                    // an underscore in the table name is a wildcard here too,
                    // so a stats_history table would answer for stats_history
                    // and statsXhistory alike.
                    if (!table.equalsIgnoreCase(columns.getString("TABLE_NAME"))) {
                        continue;
                    }
                    String name = columns.getString("COLUMN_NAME");
                    // The width comes off the same row as the name. It is the
                    // same question about the same column, and a second pass
                    // over getColumns would be a metadata round trip per table
                    // per start for an answer this one already has.
                    found.put(name.toLowerCase(Locale.ROOT),
                            new Stored(name, columns.getInt("DATA_TYPE"),
                                    columns.getInt("COLUMN_SIZE")));
                }
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        return found;
    }

    /**
     * A column as the live table has it.
     *
     * @param name     the name the driver reports, in the engine's own case
     * @param dataType the {@link java.sql.Types} constant the driver reports
     * @param size     {@code COLUMN_SIZE}: characters for a text column
     */
    record Stored(@NotNull String name, int dataType, int size) {
    }

    /**
     * The columns a live table insists on that the record does not declare.
     *
     * <p>Every entity in the previous library extended a base class carrying
     * {@code created_at} and {@code updated_at}, written {@code NOT NULL}. A
     * plugin that migrates keeps its table, so its first insert names only the
     * columns the record has and the row is refused for a column the code no
     * longer knows exists.
     *
     * <p>Only columns with no default and no generated value are returned:
     * anything the engine can fill in for itself is already handled.
     *
     * @param connection an open connection
     * @param table      the folded table name
     * @param model      what the record declares
     * @return the orphaned names as the engine spells them
     */
    private @NotNull List<Orphan> orphanedRequiredColumns(@NotNull Connection connection,
                                                          @NotNull String table,
                                                          @NotNull EntityModel<?> model)
            throws SQLException {
        Set<String> declared = new HashSet<>();
        for (ColumnModel column : model.columns()) {
            declared.add(column.name().toLowerCase(Locale.ROOT));
        }
        DatabaseMetaData metadata = connection.getMetaData();
        List<Orphan> orphans = new ArrayList<>();
        for (String candidate : metadataForms(table)) {
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), schemaOf(connection),
                    candidate, null)) {
                while (columns.next()) {
                    if (!table.equalsIgnoreCase(columns.getString("TABLE_NAME"))) {
                        continue;
                    }
                    String name = columns.getString("COLUMN_NAME");
                    if (declared.contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    if (columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls) {
                        continue;
                    }
                    // A column that fills itself in needs nothing from us, and
                    // an identity column must not be touched at all.
                    if (columns.getString("COLUMN_DEF") != null
                            || "YES".equalsIgnoreCase(columns.getString("IS_AUTOINCREMENT"))
                            || "YES".equalsIgnoreCase(columns.getString("IS_GENERATEDCOLUMN"))) {
                        continue;
                    }
                    // MySQL has to restate the type to drop the constraint.
                    orphans.add(new Orphan(name, columns.getString("TYPE_NAME")));
                }
            }
            if (!orphans.isEmpty()) {
                return orphans;
            }
        }
        return orphans;
    }

    /** A column the table requires and the record does not declare. */
    private record Orphan(String name, String type) {
    }

    /**
     * The indexes on a live table: their names, and the columns each covers.
     *
     * <p>{@code getIndexInfo} returns one row per column of per index, with an
     * {@code ORDINAL_POSITION} saying where in the key that column sits, so the
     * rows are grouped by name and ordered by position to reconstruct the real
     * shape of every index that is already there.
     *
     * <p>That reconstruction is what makes a composite index recognisable as
     * itself rather than merely as a name. It matters because the two ways an
     * index can be "already there" have opposite right answers: an index over
     * the same columns under a name an operator chose must not be duplicated,
     * while an index that carries <em>our</em> generated name but covers
     * different columns is a stale index from a release whose {@code @Index}
     * listed something else — and skipping that one on a name match would leave
     * a table permanently without the index the code now asks for, silently,
     * forever.
     *
     * <p>{@code ASC_OR_DESC} is deliberately not compared. It is documented as
     * nullable and several drivers return {@code null} for every row regardless
     * of how the index was built, so a comparison against it would read as
     * "the direction changed" on every start and drop and rebuild a working
     * index each time. Direction is written into the {@code CREATE} and left
     * there; what is verified is the columns and their order, which every driver
     * does report.
     *
     * @param connection an open connection
     * @param table      the folded table name
     * @return each index name, lower case, mapped to what it covers
     */
    @NotNull Map<String, Existing> indexesOf(@NotNull Connection connection,
                                             @NotNull String table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : metadataForms(table)) {
            // getIndexInfo takes an exact name, not a pattern, so there is no
            // wildcard to filter here — only the case to get right.
            Map<String, SortedMap<Integer, String>> columns = new LinkedHashMap<>();
            Map<String, Boolean> unique = new LinkedHashMap<>();
            try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), schemaOf(connection),
                    candidate, false, false)) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    String column = indexes.getString("COLUMN_NAME");
                    if (name == null || column == null) {
                        // tableIndexStatistic rows carry a null column: they
                        // describe the table's row count, not an index.
                        continue;
                    }
                    String key = name.toLowerCase(Locale.ROOT);
                    columns.computeIfAbsent(key, absent -> new TreeMap<>())
                            .put(indexes.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
                    unique.put(key, !indexes.getBoolean("NON_UNIQUE"));
                }
            } catch (SQLException unsupported) {
                // Some drivers throw rather than return an empty set for a
                // table they cannot see. An empty answer here only costs a
                // CREATE INDEX that the duplicate-code path then swallows.
                return Map.of();
            }
            if (!columns.isEmpty()) {
                Map<String, Existing> found = new LinkedHashMap<>(columns.size() * 2);
                columns.forEach((name, positions) -> found.put(name,
                        new Existing(List.copyOf(positions.values()),
                                Boolean.TRUE.equals(unique.get(name)))));
                return Map.copyOf(found);
            }
        }
        return Map.of();
    }

    /**
     * An index that is already on a live table.
     *
     * @param columns the columns it covers, lower case, in key order
     * @param unique  whether it enforces uniqueness
     */
    record Existing(@NotNull List<String> columns, boolean unique) {
    }

    /**
     * Both spellings of an identifier to try against {@code DatabaseMetaData}.
     *
     * <p>Lower case first, because that is what this library stores and what
     * every table it created has. Upper case second, for a table created by
     * hand on H2 or by an older plugin that let the engine fold the name.
     */
    private @NotNull List<String> metadataForms(@NotNull String identifier) {
        String folded = dialect.foldForMetadata(identifier);
        String upper = folded.toUpperCase(Locale.ROOT);
        return folded.equals(upper) ? List.of(folded) : List.of(folded, upper);
    }

    /**
     * The schema to scope a metadata query to, or {@code null} for all of them.
     *
     * <p>Postgres puts every table in {@code public} unless told otherwise and
     * reports its catalog as the database, while MySQL has no schemas at all
     * and reports the database as the catalog. Asking the connection rather
     * than assuming keeps a lookup from matching a same-named table in another
     * schema the user happens to be able to see.
     */
    private static @Nullable String schemaOf(@NotNull Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError unsupported) {
            // AbstractMethodError: getSchema arrived in JDBC 4.1 and an old
            // driver on a server's classpath can predate it.
            return null;
        }
    }

    // ------------------------------------------------------------- migration

    /**
     * Brings the columns of a live table up to what the record declares: the
     * ones stored under another case are renamed, the ones genuinely absent are
     * added.
     *
     * <p>Both from a single metadata read, because they are the same question
     * asked of the same map. Splitting them cost an outage: the rename used to
     * run only when the <em>table</em> name had been folded, while the add
     * compared against names lower-cased on the way in — so a table whose name
     * was fine but whose {@code CREATED_AT} had been folded by an
     * ExyliaCommons-era {@code CREATE} looked complete to the add and was never
     * offered to the rename. The column was neither added nor reconciled, and
     * every read and write against it failed with
     * {@code Column "created_at" not found}, forever. Production had exactly
     * that on {@code killeffect_players}: four columns lower case, two upper.
     *
     * <p>A column the table has and the record does not is left alone, in
     * either direction: it may belong to another plugin's view of the same
     * table, and neither dropping it nor renaming it is recoverable.
     *
     * @param connection an open connection
     * @param model      the record model
     * @param table      the folded table name
     * @param added      collects the columns added, in model order
     * @param renamed    collects the columns reconciled, in model order
     * @param widened    collects the text columns made wider, in model order
     * @param narrow     collects the ones that had to be widened and could not
     */
    private void reconcileColumns(@NotNull Connection connection,
                                  @NotNull EntityModel<?> model,
                                  @NotNull String table,
                                  @NotNull List<String> added,
                                  @NotNull List<String> renamed,
                                  @NotNull List<String> relaxed,
                                  @NotNull List<String> widened,
                                  @NotNull List<String> narrow) throws SQLException {
        Map<String, Stored> stored = storedColumns(connection, table);
        if (stored.isEmpty()) {
            // The table exists but nothing could be read about it — a driver
            // that scoped the query differently than expected. Adding every
            // column blind would throw on the first one that is already there;
            // doing nothing leaves the table exactly as it was.
            return;
        }
        for (ColumnModel column : model.columns()) {
            String wanted = dialect.identifier(column.name());
            Stored actual = stored.get(wanted);
            if (actual == null) {
                try {
                    execute(connection, dialect.addColumn(model.table(), column));
                    added.add(wanted);
                } catch (SQLException failure) {
                    if (!dialect.isDuplicateColumn(failure)) {
                        throw failure;
                    }
                    // Lost a race with another server on the same database. The
                    // column is there, which is all that was wanted.
                }
                continue;
            }
            if (!wanted.equals(actual.name())) {
                // There, but spelled in the engine's own folding. Renaming once
                // is what makes the existing rows reachable; the alternative,
                // addressing it in its own case forever, spreads the engine's
                // folding rules through every statement the library emits.
                execute(connection, dialect.renameColumn(table, actual.name(), wanted));
                renamed.add(wanted);
            }
            // Asked of a renamed column too: the case it was stored under has
            // nothing to do with how wide it is.
            if (needsWidening(actual, widthOf(dialect.columnType(column)))) {
                try {
                    execute(connection, dialect.widenColumn(table, column));
                    widened.add(wanted);
                } catch (SQLException refused) {
                    // Best-effort, like relaxOrphanedColumns: a database that
                    // refuses the alteration must not keep the plugin from
                    // starting. Unlike it, this one is worth saying out loud —
                    // the column is still too narrow, so the first value that
                    // does not fit is refused or, on a non-strict MySQL,
                    // truncated into something that no longer parses back.
                    narrow.add(wanted);
                }
            }
        }
        relaxOrphanedColumns(connection, model, table, relaxed);
    }

    /**
     * How many characters a dialect's type string declares, if it says.
     *
     * <p>Read back off {@link Dialect#columnType(ColumnModel)} rather than off
     * the annotation, because the two do not always agree and the type is the
     * one that decides: a {@code UUID} is stored as {@code VARCHAR(36)} on every
     * engine whatever {@code length} was asked for, so comparing the annotation
     * against the live column would read a 36-character column as 219
     * characters too narrow and "widen" it into something smaller.
     *
     * @param type a type from a dialect
     * @return the width, or {@link Column#UNBOUNDED} when the type names none
     */
    static int widthOf(@NotNull String type) {
        int open = type.indexOf('(');
        int close = type.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return Column.UNBOUNDED;
        }
        try {
            return Integer.parseInt(type.substring(open + 1, close).trim());
        } catch (NumberFormatException notAWidth) {
            // DECIMAL(38,10) and friends. Not text, so nothing to widen.
            return Column.UNBOUNDED;
        }
    }

    /**
     * Whether a live column is narrower than the record now needs it.
     *
     * <p>Only bounded text is ever a candidate. A numeric column is left alone
     * outright — precision is not a width and changing it is not this method's
     * business — and so is a column the engine already stores unboundedly,
     * whether it says so by type ({@code CLOB}, {@code TEXT} reported as
     * {@code LONGVARCHAR}) or by reporting a size no {@code VARCHAR} anybody
     * declares could reach: Postgres and H2 both report their unlimited text as
     * a {@code VARCHAR} of {@link Integer#MAX_VALUE}, and treating that as a
     * bounded column would emit an {@code ALTER} on every single start.
     *
     * <p>Never narrows, in either direction. A column stored wider than the
     * record declares is left exactly as it is: it may be another plugin's view
     * of the same table, and shrinking it truncates rows. That is the same rule
     * the rest of this class applies to a column no record declares.
     *
     * @param stored   the column as the table has it
     * @param declared the width the model's type asks for, or
     *                 {@link Column#UNBOUNDED}
     */
    static boolean needsWidening(@NotNull Stored stored, int declared) {
        boolean boundedText = switch (stored.dataType()) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> true;
            default -> false;
        };
        if (!boundedText || stored.size() <= 0 || stored.size() >= EFFECTIVELY_UNBOUNDED) {
            return false;
        }
        return declared == Column.UNBOUNDED || declared > stored.size();
    }

    /**
     * The size at which a reported {@code VARCHAR} is really the engine's
     * unlimited text type.
     *
     * <p>A million characters, which is nothing any schema declares as a bound
     * — MySQL cannot store a {@code VARCHAR} past 65,535 bytes at all — and far
     * below what the engines report for text that has no bound: H2 answers
     * 1,000,000,000 for its unlimited {@code CHARACTER VARYING} and Postgres
     * answers {@link Integer#MAX_VALUE} for {@code text}. Reading either as a
     * bounded column would emit an {@code ALTER} on every single start.
     */
    private static final int EFFECTIVELY_UNBOUNDED = 1_000_000;

    /**
     * Lets a table accept a row without the columns the record dropped.
     *
     * <p>The column keeps its data and its name: only the refusal goes. A
     * column this library never writes cannot be filled in by guessing, and
     * inventing a value would be writing a lie into a column that means
     * something — a creation time that is not when the row was created.
     *
     * <p>Dropping it instead would take the existing rows' values with it, and
     * a plugin that has not migrated yet still reads them.
     *
     * <p>Best-effort: a database that refuses the alteration, or a user without
     * the rights to make it, is left exactly as it was rather than kept from
     * starting.
     */
    private void relaxOrphanedColumns(@NotNull Connection connection,
                                      @NotNull EntityModel<?> model,
                                      @NotNull String table,
                                      @NotNull List<String> relaxed) {
        List<Orphan> orphans;
        try {
            orphans = orphanedRequiredColumns(connection, table, model);
        } catch (SQLException unreadable) {
            return;
        }
        for (Orphan found : orphans) {
            String orphan = found.name();
            try {
                execute(connection, dialect.dropNotNull(table, orphan, found.type()));
                relaxed.add(orphan);
            } catch (SQLException refused) {
                // Left exactly as it was. Every write to this table will keep
                // failing, which the caller learns from the failure itself
                // rather than from a line at boot nobody was reading.
            }
        }
    }

    /**
     * Creates the indexes a model asks for, once.
     *
     * <p>MySQL has to be asked first, because it cannot carry the guard in the
     * statement. The other three are asked too, for a different reason: an
     * {@code IF NOT EXISTS} that quietly does nothing is indistinguishable from
     * one that created an index, so without the lookup this method would report
     * every index as newly created on every single start, and the report exists
     * precisely so that a console line means something happened.
     *
     * <p>The guard still goes in the statement where the engine parses it, and
     * a collision is still forgiven by vendor code: the lookup and the
     * {@code CREATE} are not atomic, and two servers starting together race.
     *
     * <p>What counts as "already there" is decided by the columns an existing
     * index covers rather than by its name — see {@link #covered} and
     * {@link #indexesOf}. A composite index is a shape, and the name is only how
     * this library happens to spell it.
     *
     * <p>An index whose <em>name</em> is already taken by an index over
     * different columns is reported as blocked rather than as created. That is
     * the one case where "already exists" is not good enough: the name usually
     * comes from an {@code @Index} that kept its name and changed its columns, so
     * the old index still holds the name, the {@code CREATE} either does nothing
     * (where {@code IF NOT EXISTS} parses) or fails as a duplicate (on MySQL),
     * and claiming to have created it would leave the table silently without the
     * index the code now asks for on every start, forever.
     *
     * @param freshTable whether the table was created a moment ago, in which
     *                   case it provably has no indexes yet and the lookup is
     *                   a round trip for a known answer
     * @param created    filled with the indexes actually created
     * @param blocked    filled with the indexes whose name is held by another
     */
    private void createIndexes(@NotNull Connection connection,
                               @NotNull EntityModel<?> model,
                               @NotNull String table,
                               boolean freshTable,
                               @NotNull List<String> created,
                               @NotNull List<String> blocked) throws SQLException {
        // One list, whether an index came from @Indexed on a component or from
        // @Index on the record. EntityModel already excluded the primary key,
        // which every engine indexes on its own.
        List<IndexModel> wanted = model.indexes();
        if (wanted.isEmpty()) {
            return;
        }
        Map<String, Existing> existing = freshTable
                ? Map.of()
                : indexesOf(connection, table);

        for (IndexModel index : wanted) {
            if (covered(existing, index)) {
                continue;
            }
            String name = dialect.indexName(index);
            if (existing.containsKey(name.toLowerCase(Locale.ROOT))) {
                // The name is taken by an index that does not cover what this one
                // wants — covered() already said so. Nothing can be created under
                // it, and pretending otherwise is worse than saying so.
                blocked.add(name);
                continue;
            }
            try {
                execute(connection, dialect.createIndex(model.table(), index));
                created.add(name);
            } catch (SQLException failure) {
                if (!dialect.isDuplicateIndex(failure)) {
                    throw failure;
                }
                // Lost a race with another server on the same database, or the
                // index exists under a name the metadata lookup could not see.
                // Either way it is there, which is all that was wanted.
            }
        }
    }

    /**
     * Whether an index the model asks for is already on the table.
     *
     * <p>Answered by columns, not by name. Any live index whose leading columns
     * are the ones this index wants, in the same order, already answers every
     * query this one would: a key on {@code (kit_id, elo, wins)} serves a lookup
     * that filters {@code kit_id} and sorts {@code elo} exactly as well as a key
     * on {@code (kit_id, elo)} does, so creating the shorter one would pay for a
     * second B-tree on every insert to answer nothing new.
     *
     * <p>A prefix match rather than an exact one for the same reason it is not a
     * name match: an operator who widened an index by hand, or an earlier
     * release that asked for more columns, has left something better than what
     * is being asked for. Only the other direction — a live index that is a
     * <em>shorter</em> prefix of the wanted one — means the index is genuinely
     * missing, and that is the case this returns false for.
     *
     * <p>A unique index is only covered by a unique one, whatever the columns
     * say. Uniqueness is a constraint and not an optimisation: a plain index
     * over the same columns answers the same queries and enforces nothing, so
     * treating it as covering would silently drop the guarantee a record asked
     * for and let in the duplicate row it was written to refuse. The reverse is
     * fine — a unique index covers a non-unique request completely.
     */
    private static boolean covered(@NotNull Map<String, Existing> existing,
                                   @NotNull IndexModel index) {
        List<String> wanted = index.columns();
        for (Existing present : existing.values()) {
            if (index.unique() && !present.unique()) {
                continue;
            }
            List<String> columns = present.columns();
            if (columns.size() < wanted.size()) {
                continue;
            }
            // A unique constraint over more columns than were asked for is not
            // the constraint that was asked for: uniqueness of (a) is stricter
            // than uniqueness of (a, b), so a wider unique index does not
            // enforce it. Only an exact column list will do for a unique index.
            if (index.unique() && columns.size() != wanted.size()) {
                continue;
            }
            boolean prefix = true;
            for (int position = 0; position < wanted.size(); position++) {
                if (!columns.get(position).equalsIgnoreCase(wanted.get(position))) {
                    prefix = false;
                    break;
                }
            }
            if (prefix) {
                return true;
            }
        }
        return false;
    }

    private static void execute(@NotNull Connection connection, @NotNull String sql) throws SQLException {
        // Statement, not PreparedStatement: DDL carries no parameters, and a
        // prepared DDL statement is refused outright by some drivers.
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
