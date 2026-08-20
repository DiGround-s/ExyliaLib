package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Everything one SQL engine says differently from the others.
 *
 * <p>Four engines are supported and no two of them agree on the statements this
 * library has to write. The differences are not stylistic: each one below was
 * measured against a live engine, and each one is a query that either fails to
 * parse or, worse, parses and does the wrong thing.
 *
 * <h2>What actually differs, and what it costs to get wrong</h2>
 * <ul>
 *   <li><b>Upsert.</b> MySQL and MariaDB cannot share a single string. MySQL
 *       8.0.20 deprecates {@code VALUES(col)} and wants a row alias
 *       ({@code AS new}); MariaDB refuses to parse that alias at all. Postgres
 *       needs {@code ON CONFLICT} with an explicit conflict target — omitting
 *       it is a hard error, not a default. H2 needs {@code MERGE ... KEY}.
 *       One "portable" upsert string is therefore impossible, and pretending
 *       otherwise means the plugin runs on the engine it was tested against
 *       and throws a syntax error on the other three.</li>
 *   <li><b>Types.</b> {@code FLOAT} is 8 bytes on H2 and Postgres and 4 on
 *       MySQL, so a schema written with {@code FLOAT} silently changes
 *       precision when a server migrates. {@code BOOLEAN} does not exist on
 *       MySQL. Postgres has a native {@code uuid} type that rejects
 *       {@code setString}. See {@link #columnType(ColumnModel)}.</li>
 *   <li><b>{@code CREATE INDEX IF NOT EXISTS}.</b> H2, MariaDB and Postgres
 *       accept it. MySQL does not — it is a syntax error, verified — so MySQL
 *       has to ask first or swallow error 1061 afterwards. See
 *       {@link #supportsCreateIndexIfNotExists()}. The per-column
 *       {@code ASC | DESC} inside the parentheses, on the other hand, is one of
 *       the few things all four spell identically; see
 *       {@link #createIndex(String, IndexModel)}.</li>
 *   <li><b>Identifier folding.</b> H2 upper-cases unquoted identifiers,
 *       Postgres lower-cases them, MySQL stores table names however the file
 *       system and {@code lower_case_table_names} feel about it. This library
 *       never plays that game: every identifier is folded to lower case by
 *       {@link #identifier(String)} and always quoted by {@link #quote(String)},
 *       so what is written is what is stored on all four.</li>
 * </ul>
 *
 * <h2>What a dialect never does</h2>
 * It never interpolates a value. Every statement produced here carries
 * {@code ?} placeholders and nothing else, including {@code LIMIT} and
 * {@code OFFSET}: the SQL string for page one and page ninety is the same
 * string, which is what lets a statement be prepared once and reused, and what
 * makes SQL injection structurally impossible rather than merely unlikely.
 *
 * <h2>Threads</h2>
 * Implementations are stateless and immutable; one instance serves every thread
 * of a backend. Statement text is deterministic for a given model, so callers
 * are free to cache it.
 *
 * @since 1.24.0
 */
public interface Dialect {

    /**
     * Which way a column is sorted.
     *
     * <p>Deliberately without a NULLS-first/last option. Postgres sorts nulls
     * last ascending and first descending, MySQL does the opposite, and MySQL 8
     * has no {@code NULLS LAST} syntax to reconcile them with. Emitting a
     * clause only three engines understand would trade a portable ordering
     * difference for an outright syntax error on the fourth.
     *
     * @param column     the column name, as the database has it
     * @param descending whether to sort descending
     */
    record Sort(@NotNull String column, boolean descending) {

        /** Ascending order on a column. */
        public static @NotNull Sort asc(@NotNull String column) {
            return new Sort(column, false);
        }

        /** Descending order on a column. */
        public static @NotNull Sort desc(@NotNull String column) {
            return new Sort(column, true);
        }
    }

    /**
     * The dialect for an engine name.
     *
     * @param engine {@code h2}, {@code mysql}, {@code mariadb} or
     *               {@code postgres} / {@code postgresql}, in any case
     * @return the dialect
     * @throws IllegalArgumentException if no dialect matches, naming the ones
     *                                  that exist
     */
    static @NotNull Dialect of(@NotNull String engine) {
        return switch (engine.toLowerCase(Locale.ROOT).trim()) {
            case "h2" -> H2Dialect.INSTANCE;
            case "mysql" -> MySQLDialect.INSTANCE;
            case "mariadb" -> MariaDBDialect.INSTANCE;
            case "postgres", "postgresql", "pgsql" -> PostgresDialect.INSTANCE;
            default -> throw new IllegalArgumentException("No SQL dialect named '" + engine
                    + "'. The database module speaks h2, mysql, mariadb and postgres.");
        };
    }

    // -------------------------------------------------------------- identity

    /** The engine name this dialect answers to, lower case. */
    @NotNull String id();

    /**
     * The JDBC driver class, spelled out.
     *
     * <p>Handed to Hikari rather than left to {@code DriverManager}, whose
     * service-loader discovery walks the thread context classloader. Under a
     * server's plugin classloaders that is whichever plugin happens to be
     * calling, so discovery finds the driver on one server and not on the next
     * — a {@code No suitable driver} that reproduces nowhere.
     */
    @NotNull String driverClassName();

    /**
     * The JDBC URL for a set of settings, with the parameters this library
     * insists on already in it.
     *
     * @param settings where and how to connect
     * @return the URL
     */
    @NotNull String jdbcUrl(@NotNull SqlSettings settings);

    /**
     * How this dialect wants its connection pool shaped.
     *
     * <p>Not a constant, because an embedded engine and a networked one want
     * opposite things: H2 is a method call away and wants a tiny pool that
     * never recycles, while a networked engine wants enough connections to
     * cover the background pool and a lifetime short enough to lose a
     * connection before a firewall does it for us.
     *
     * @param settings the settings the pool was opened with
     * @return the pool shape
     */
    @NotNull PoolProfile poolProfile(@NotNull SqlSettings settings);

    /**
     * A pool shape, in the units Hikari takes.
     *
     * @param maximumPoolSize  connections at most
     * @param minimumIdle      connections kept open
     * @param maxLifetimeMillis how long a connection may live, {@code 0} for forever
     * @param keepaliveMillis  how often an idle connection is pinged, {@code 0} to never
     */
    record PoolProfile(int maximumPoolSize, int minimumIdle, long maxLifetimeMillis, long keepaliveMillis) {
    }

    // ----------------------------------------------------------- identifiers

    /**
     * Folds a name the way this library stores it: lower case, always.
     *
     * <p>Case is the one thing about an identifier that no two engines agree
     * on, and every automatic answer is wrong somewhere. Choosing lower case
     * once and quoting it everywhere makes the stored name identical on all
     * four engines, which is what lets metadata lookups, index names and
     * generated SQL match without asking the server how it feels about case.
     *
     * <p>Column names in MySQL and MariaDB are case-insensitive, so folding a
     * legacy {@code displayName} column to {@code displayname} still addresses
     * the same column in the tables the ecosystem already has. It is not a
     * rename and it does not become one.
     *
     * @param identifier a table or column name
     * @return the stored form
     */
    default @NotNull String identifier(@NotNull String identifier) {
        // Locale.ROOT: in a Turkish locale "I".toLowerCase() is "ı", which
        // would produce a column name no engine has ever heard of, on the one
        // server whose operating system is set to tr_TR.
        return identifier.toLowerCase(Locale.ROOT);
    }

    /**
     * Quotes an identifier so the engine reads it verbatim.
     *
     * <p>Always applied, never conditionally. An unquoted identifier is folded
     * by the server, may collide with a reserved word the next release adds
     * ({@code rank} became reserved in MySQL 8.0), and on MySQL depends on
     * {@code lower_case_table_names}, a setting that cannot be changed after
     * initialisation and differs between a Linux server and the Windows box a
     * developer tests on.
     *
     * @param identifier a name, already folded by {@link #identifier(String)}
     * @return the quoted form
     */
    @NotNull String quote(@NotNull String identifier);

    /**
     * The name this dialect gives an index, folded as it is stored.
     *
     * <p>The name itself is derived by {@link IndexModel} and not here: it has
     * to be identical on every engine so that a model compiled once produces
     * one index whichever backend it lands on, and so that the Mongo side and
     * the SQL side agree about what an index is called. What a dialect
     * contributes is the folding, which is the one thing about an identifier
     * the engines genuinely disagree about.
     *
     * @param index the compiled index
     * @return the index name, folded and short enough for every engine
     */
    default @NotNull String indexName(@NotNull IndexModel index) {
        return identifier(index.name());
    }

    /**
     * The identifier as the driver's {@code DatabaseMetaData} has it stored.
     *
     * <p>A hook rather than a constant because the engines differ, and because
     * a caller must still try both cases: the answer depends on the engine, on
     * whether the table was created by this library or by hand, and on MySQL
     * also on the host file system. See {@code SqlSchema}, which tries both.
     *
     * @param identifier a folded identifier
     * @return the form to pass to {@code getTables} / {@code getColumns}
     */
    default @NotNull String foldForMetadata(@NotNull String identifier) {
        return identifier;
    }

    // ------------------------------------------------------------------- DDL

    /**
     * The SQL type a column is stored as.
     *
     * @param column the column
     * @return a SQL type, ready to paste into a DDL statement
     */
    @NotNull String columnType(@NotNull ColumnModel column);

    /**
     * {@code CREATE TABLE IF NOT EXISTS} for a whole model.
     *
     * <p>Columns and the primary key only. Uniqueness and indexes are separate
     * statements ({@link #createIndex}) so that a column added to a live table
     * years later gets exactly the same treatment as one that was there from
     * the start — a constraint that only exists inside a {@code CREATE TABLE}
     * is a constraint the second deploy silently does without.
     *
     * @param model the record model
     * @return one statement
     */
    @NotNull String createTable(@NotNull EntityModel<?> model);

    /**
     * {@code CREATE INDEX} for one index, over one column or several.
     *
     * <p>Emits the direction of every column, because that is what makes a
     * composite index answer the query it was written for:
     *
     * <pre>{@code
     * CREATE INDEX "idx_stats_kit_id_elo" ON "stats" ("kit_id" ASC, "elo" DESC)
     * }</pre>
     *
     * <p>All four engines accept a per-column {@code ASC | DESC} in the key part
     * of a {@code CREATE INDEX}, verified against each one's own grammar. It is
     * only on MySQL and MariaDB that the keyword is comparatively recent as a
     * <em>stored</em> order — MySQL 8.0 and MariaDB 10.8 build a genuinely
     * descending index, and older versions parse the keyword and ignore it. That
     * asymmetry is why the keyword is always written and never conditionally:
     * an engine that ignores it builds the ascending index it would have built
     * anyway, so the statement is correct everywhere and optimal where the
     * engine is new enough. Omitting it would leave the index ascending even on
     * the engines that can do better.
     *
     * <p>{@code UNIQUE} when the index is, and {@code IF NOT EXISTS} only where
     * the engine parses it; see {@link #supportsCreateIndexIfNotExists()}.
     *
     * @param table the table name
     * @param index the compiled index
     * @return one statement
     */
    @NotNull String createIndex(@NotNull String table, @NotNull IndexModel index);

    /**
     * Whether {@code CREATE INDEX IF NOT EXISTS} parses on this engine.
     *
     * <p>False on MySQL, and only on MySQL. It is a syntax error there, which
     * means the guard cannot be written into the statement and has to be either
     * a metadata lookup first or error 1061 swallowed afterwards
     * ({@link #isDuplicateIndex(SQLException)}).
     */
    boolean supportsCreateIndexIfNotExists();

    /**
     * {@code ALTER TABLE ... ADD COLUMN} for a column a live table is missing.
     *
     * <p>Never emits {@code NOT NULL}, whatever the column says. A table with
     * rows in it cannot gain a non-null column without a default: Postgres
     * rejects the statement outright, and MySQL accepts it and invents a
     * default of {@code 0} or {@code ''} for every existing row, which is the
     * same corruption with none of the warning. The nullability in the model
     * still applies to tables this library creates from scratch.
     *
     * @param table  the table name
     * @param column the column to add
     * @return one statement
     */
    @NotNull String addColumn(@NotNull String table, @NotNull ColumnModel column);

    /**
     * {@code ALTER TABLE ... RENAME TO} for a table stored under another case.
     *
     * <p>Run once, when a table created unquoted by an older plugin is found
     * folded to the engine's own case. Both names are quoted, so the statement
     * says exactly which table becomes which and does not fold again halfway.
     *
     * <p>The same syntax on all four engines, which is why it is not abstract:
     * MySQL also accepts {@code RENAME TABLE a TO b}, but the {@code ALTER}
     * spelling is the one Postgres, H2 and MariaDB share.
     *
     * @param from the name the table is stored under
     * @param to   the name this library addresses it by
     * @return one statement
     */
    default @NotNull String renameTable(@NotNull String from, @NotNull String to) {
        return "ALTER TABLE " + quote(from) + " RENAME TO " + quote(to);
    }

    /**
     * {@code ALTER TABLE ... RENAME COLUMN} for a column stored under another
     * case, alongside {@link #renameTable}.
     *
     * <p>Standard SQL since 2016 and supported by all four engines in the
     * versions this library targets — MySQL from 8.0, MariaDB from 10.5.2.
     * A dialect for an older server would override it with the
     * {@code CHANGE COLUMN} spelling, which needs the type repeated.
     *
     * @param table the table, already in the case this library uses
     * @param from  the name the column is stored under
     * @param to    the name this library addresses it by
     * @return one statement
     */
    default @NotNull String renameColumn(@NotNull String table, @NotNull String from,
                                         @NotNull String to) {
        return "ALTER TABLE " + quote(table) + " RENAME COLUMN " + quote(from)
                + " TO " + quote(to);
    }

    /**
     * {@code ALTER TABLE ... ALTER COLUMN ... DROP NOT NULL} for a column the
     * table requires and no record declares.
     *
     * <p>The ANSI spelling, which H2 and Postgres both take. MySQL and MariaDB
     * cannot drop a constraint without restating the column's type, so they
     * override this.
     *
     * @param table  the table, already in the case this library uses
     * @param column the column as the engine spells it
     * @param type   the column's declared type, as the driver reports it
     * @return one statement
     */
    default @NotNull String dropNotNull(@NotNull String table, @NotNull String column,
                                        @NotNull String type) {
        return "ALTER TABLE " + quote(table) + " ALTER COLUMN " + quote(column)
                + " DROP NOT NULL";
    }

    // ------------------------------------------------------------------- DML

    /**
     * A plain {@code INSERT} of every column, in model order.
     *
     * @param model the record model
     * @return one statement with one placeholder per column
     */
    @NotNull String insert(@NotNull EntityModel<?> model);

    /**
     * An {@code INSERT} that leaves a generated key for the engine to fill.
     *
     * <p>Every column but the key, so the engine's counter supplies it. The key
     * cannot simply be bound as {@code null}: MySQL accepts that and treats it
     * as "pick one", Postgres rejects it against a {@code NOT NULL} identity
     * column, and the two behaviours would diverge the day a server moves
     * between them.
     *
     * <p>The placeholders are {@link EntityModel#insertColumns()} in order, so a
     * caller binds {@link EntityModel#insertValues(Object)} straight through.
     *
     * @param model the record model
     * @return one statement with one placeholder per written column
     * @since 1.32.0
     */
    @NotNull String insertGenerated(@NotNull EntityModel<?> model);

    /**
     * Insert-or-update of every column, in model order.
     *
     * <p>The key columns are a required argument rather than something read off
     * the model, because the engine needs to be told which conflict it is
     * resolving and there is no safe default: Postgres treats a missing
     * conflict target as an error, and H2's {@code MERGE} without a
     * {@code KEY} clause falls back to the primary key of whatever table
     * happens to be there.
     *
     * <p>The placeholders are the columns in model order and nothing else. No
     * dialect here binds a value twice, so a caller binds
     * {@link EntityModel#values(Object)} straight through.
     *
     * @param model       the record model
     * @param keyColumns  the columns that decide a conflict, usually the primary key
     * @return one statement with one placeholder per column
     * @throws IllegalArgumentException if the key list is empty
     */
    @NotNull String upsert(@NotNull EntityModel<?> model, @NotNull List<String> keyColumns);

    /**
     * {@code UPDATE} of every column but the key, matched on the key.
     *
     * <p>Plain standard SQL, so no dialect overrides it. Unlike
     * {@link #upsert} it never creates a row: a key that matches nothing
     * changes nothing, which is what an update of a row somebody deleted
     * meanwhile should do.
     *
     * <p>The placeholders are every non-key column in model order, then the
     * key last.
     *
     * @param model the record model
     * @return one statement
     */
    default @NotNull String update(@NotNull EntityModel<?> model) {
        StringBuilder sql = new StringBuilder(96)
                .append("UPDATE ").append(quote(identifier(model.table()))).append(" SET ");
        boolean first = true;
        for (ColumnModel column : model.columns()) {
            if (column.name().equals(model.id().name())) {
                continue;
            }
            if (!first) {
                sql.append(", ");
            }
            sql.append(quote(identifier(column.name()))).append(" = ?");
            first = false;
        }
        return sql.append(" WHERE ").append(quote(identifier(model.id().name())))
                .append(" = ?").toString();
    }

    /**
     * {@code SELECT} of every column, with optional filter, order and page.
     *
     * <p>Placeholders are bound in this order: the where values, then the
     * limit, then the offset.
     *
     * <p>{@code limit} and {@code offset} shape the statement but do not appear
     * in it — both are bound. A page size of ten with offset zero and the same
     * page size with offset nine hundred produce byte-identical SQL, so the
     * prepared statement and the engine's plan cache are hit rather than
     * refilled once per page.
     *
     * @param model        the record model
     * @param whereColumns columns compared with {@code =}, joined by {@code AND}, may be empty
     * @param order        sort columns, may be empty
     * @param limit        rows at most, {@code 0} or less for no limit
     * @param offset       rows skipped, requires a limit
     * @return one statement
     * @throws IllegalArgumentException if an offset is asked for without a limit
     */
    @NotNull String select(@NotNull EntityModel<?> model,
                           @NotNull List<String> whereColumns,
                           @NotNull List<Sort> order,
                           int limit,
                           int offset);

    /**
     * One page of a whole-table walk, in primary-key order.
     *
     * <p>Keyset pagination, not {@code OFFSET}. The two are not
     * interchangeable and the difference is what makes this method exist:
     * {@code LIMIT ? OFFSET ?} makes the engine produce and discard every row
     * before the page, so walking a table of <i>n</i> rows in pages costs
     * O(n²), and without a total order the engine is free to return the rows in
     * a different order per page — so a row can appear on two pages and another
     * on none. ExyliaCommons paged exactly that way, with no {@code ORDER BY}
     * at all, which is why its exports silently duplicated and dropped rows.
     * A key comparison seeks straight into the primary key's index and the
     * order is total, so every row is seen exactly once.
     *
     * <pre>{@code
     * SELECT "uuid", "elo" FROM "stats" ORDER BY "uuid" LIMIT ?              // after = false
     * SELECT "uuid", "elo" FROM "stats" WHERE "uuid" > ? ORDER BY "uuid" LIMIT ?  // after = true
     * }</pre>
     *
     * <p>Placeholders are bound in this order: the key the previous batch ended
     * on when {@code after} is true, then the batch size. The batch size is
     * bound rather than spliced, exactly as in {@link #select}, so every batch
     * of a scan is byte-identical SQL and one prepared statement.
     *
     * <p>Strictly greater than, never {@code >=}: the previous batch already
     * handed that row over, and re-reading it would hand it over twice and
     * never terminate on a table whose last batch is exactly full.
     *
     * @param model the record model
     * @param after whether to resume after a key, or start from the beginning
     * @return one statement
     * @since 1.36.0
     */
    @NotNull String scan(@NotNull EntityModel<?> model, boolean after);

    /**
     * The largest primary key in the table, or nothing when it is empty.
     *
     * <p>Only ever asked of a model whose key is generated, and only to work
     * out what {@link #resequence} has to move the counter past.
     *
     * @param model the record model
     * @return one statement, returning one row of one nullable number
     * @since 1.36.0
     */
    @NotNull String maxKey(@NotNull EntityModel<?> model);

    /**
     * Moves a generated key's counter so the next row the engine numbers
     * cannot collide with one that was written with an explicit key.
     *
     * <p>Needed because two of the four engines do not advance the counter
     * when a row arrives carrying its own key. H2 and Postgres leave it where
     * it was, so a table repopulated with ids 1..500 hands out 1 on the next
     * generated insert and fails on the primary key; MySQL and MariaDB move it
     * themselves and take this statement as a no-op restatement of where they
     * already are. Emitting it on all four is what keeps the outcome the same
     * everywhere rather than the same on the engine somebody tested against.
     *
     * <p>This is the one statement in the interface that carries a value in its
     * text, because no engine accepts a placeholder in DDL. It is safe by
     * construction and not by inspection: the parameter is a {@code long}, so
     * what lands in the string is a run of digits and there is nothing a caller
     * could put in it that is not one.
     *
     * @param model the record model, whose key must be generated
     * @param next  the value the counter must next hand out
     * @return one statement
     * @since 1.36.0
     */
    @NotNull String resequence(@NotNull EntityModel<?> model, long next);

    /**
     * {@code DELETE} with an optional filter.
     *
     * @param model        the record model
     * @param whereColumns columns compared with {@code =}, joined by {@code AND}
     * @return one statement
     * @throws IllegalArgumentException if the filter is empty, which would
     *                                  delete the table's contents
     */
    @NotNull String delete(@NotNull EntityModel<?> model, @NotNull List<String> whereColumns);

    /**
     * {@code SELECT COUNT(*)} with an optional filter.
     *
     * @param model        the record model
     * @param whereColumns columns compared with {@code =}, joined by {@code AND}, may be empty
     * @return one statement
     */
    @NotNull String count(@NotNull EntityModel<?> model, @NotNull List<String> whereColumns);

    // ------------------------------------------------------------- diagnosis

    /**
     * Whether a failure means "that index is already there".
     *
     * <p>Only ever consulted for the engines that cannot say
     * {@code IF NOT EXISTS}. Recognising it is what makes schema creation
     * idempotent, and idempotent is not optional: {@code ensureTable} runs on
     * every server start.
     *
     * @param failure what the driver threw
     * @return whether the index already existed
     */
    boolean isDuplicateIndex(@NotNull SQLException failure);

    /**
     * Whether a failure means "that column is already there".
     *
     * @param failure what the driver threw
     * @return whether the column already existed
     */
    boolean isDuplicateColumn(@NotNull SQLException failure);

    // ------------------------------------------------------------ validation

    /**
     * Everything about a model this engine cannot store correctly.
     *
     * <p>Reported rather than worked around, and reported at registration,
     * where a developer is watching. The one that matters most is an indexed
     * text column wider than 768 characters: MySQL refuses to create the index
     * outright, and MariaDB creates a 768-character prefix index instead and
     * says nothing — at which point a column declared {@code unique} stops
     * enforcing uniqueness on the full value, and two rows that differ only
     * after character 768 both get in.
     *
     * @param model the record model
     * @return the problems, in declaration order; empty when there are none
     */
    @NotNull List<String> validate(@NotNull EntityModel<?> model);

    /**
     * The widest text column this engine will index correctly, in characters.
     *
     * @return the limit, or {@link Integer#MAX_VALUE} when there is none worth stating
     */
    int maxIndexedTextLength();

    /**
     * The SQL type an unbounded text column becomes.
     *
     * @return the type name
     */
    @NotNull String unboundedTextType();

    /**
     * A page clause for a statement this dialect did not build.
     *
     * <p>{@code LIMIT ? OFFSET ?} is the only form all four engines accept:
     * MySQL rejects the standard {@code OFFSET .. FETCH}, and Postgres rejects
     * MySQL's {@code LIMIT ?, ?}. Exposed so that a hand-written query gets the
     * same treatment as a generated one.
     *
     * @param limit  rows at most, {@code 0} or less for no page clause
     * @param offset rows skipped
     * @return the clause, starting with a space, or an empty string
     */
    default @NotNull String page(int limit, int offset) {
        if (limit <= 0) {
            if (offset > 0) {
                throw new IllegalArgumentException("An offset without a limit is not portable:"
                        + " MySQL has no bare OFFSET and the usual workaround is to ask for"
                        + " 18446744073709551615 rows. Ask for a page size.");
            }
            return "";
        }
        return " LIMIT ? OFFSET ?";
    }

    /**
     * Anything that goes after the closing parenthesis of a {@code CREATE TABLE}.
     *
     * @return the suffix, starting with a space, or an empty string
     */
    default @NotNull String tableSuffix() {
        return "";
    }
}
