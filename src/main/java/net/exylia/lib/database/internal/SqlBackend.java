package net.exylia.lib.database.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * One connection pool and everything that runs statements through it.
 *
 * <p>The layer between a repository — which thinks in records — and a
 * {@link Dialect}, which thinks in strings. It owns the pool, prepares every
 * statement, binds every value, and reads every row back through the compiled
 * {@link EntityModel}.
 *
 * <pre>{@code
 * SqlBackend backend = SqlBackend.open(SqlSettings.file("h2", dataFolder.resolve("data")), "practice");
 * EntityModel<PlayerStats> model = EntityModel.of(PlayerStats.class);
 * backend.ensureTable(model);
 *
 * backend.save(model, stats);                        // insert or update, one round trip
 * PlayerStats loaded = backend.find(model, uuid);    // by primary key
 * List<PlayerStats> top = backend.select(model, List.of(), List.of(),
 *         List.of(Dialect.Sort.desc("elo")), 10, 0); // leaderboard page
 * }</pre>
 *
 * <h2>No value ever reaches a statement as text</h2>
 * Every method here builds SQL that carries {@code ?} and binds around it,
 * including {@code LIMIT} and {@code OFFSET}. That is not only about injection:
 * a statement whose text does not change is one the driver and the engine can
 * both cache, so a leaderboard asked for page one and page ninety re-parses
 * nothing.
 *
 * <h2>Threads</h2>
 * Every method blocks on I/O and must be called from a background thread —
 * {@code Tasks.runAsync}, never the main one. The class itself is safe from any
 * number of them: Hikari is, the statement-text cache is a
 * {@link ConcurrentHashMap}, and nothing else is mutable.
 *
 * @see Dialect
 * @since 1.24.0
 */
public final class SqlBackend implements AutoCloseable {

    /**
     * How long a caller waits for a connection before giving up.
     *
     * <p>Five seconds, not Hikari's default thirty. A thread stuck for thirty
     * seconds on a pool that is exhausted or a database that is gone is thirty
     * seconds of a background worker doing nothing, and on a server that queues
     * a write per player join it is a backlog nobody recovers from. Five is
     * long enough for a busy pool and short enough to surface as an error while
     * somebody is still looking at the console.
     */
    private static final long CONNECTION_TIMEOUT_MILLIS = 5_000L;

    /** Where a configured engine that is not the served one is reported. */
    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");

    private final Dialect dialect;
    private final SqlSchema schema;
    private final HikariDataSource pool;

    /**
     * Statement text, keyed by shape.
     *
     * <p>Built once per shape rather than per call. The key space is fixed by
     * the code that asks — a repository has a handful of query shapes and a
     * page number is bound, not spliced — so this cannot grow with traffic and
     * needs no eviction.
     */
    private final Map<String, String> statements = new ConcurrentHashMap<>();

    private SqlBackend(Dialect dialect, HikariDataSource pool) {
        this.dialect = dialect;
        this.schema = new SqlSchema(dialect);
        this.pool = pool;
    }

    /**
     * Opens a pool.
     *
     * @param settings where and how to connect
     * @param poolName a name for the pool's threads, usually the plugin's
     * @return the backend, with the pool already open
     * @throws IllegalStateException if the driver is missing or the database
     *                               refused the very first connection
     */
    public static @NotNull SqlBackend open(@NotNull SqlSettings settings, @NotNull String poolName) {
        Dialect dialect = Dialect.of(settings.engine());
        Dialect.PoolProfile profile = dialect.poolProfile(settings);

        HikariConfig config = new HikariConfig();
        config.setPoolName("exylia-" + poolName + "-" + dialect.id());
        config.setJdbcUrl(dialect.jdbcUrl(settings));
        // Spelled out rather than left to DriverManager's service loader, which
        // scans the thread context classloader: under a server's plugin
        // classloaders that is whichever plugin happens to be calling, so
        // discovery works on one server and throws "No suitable driver" on the
        // next with no difference in configuration.
        //
        // Hikari throws a bare RuntimeException here when the class is absent,
        // and "Failed to load driver class org.postgresql.Driver" tells an
        // operator nothing about what to do. The drivers are compileOnly and
        // arrive through the server's library loader, so a missing one is a
        // plugin.yml that was not updated — which is what the message says.
        try {
            config.setDriverClassName(dialect.driverClassName());
        } catch (RuntimeException missing) {
            throw new IllegalStateException("The " + dialect.id() + " driver ("
                    + dialect.driverClassName() + ") is not on the classpath."
                    + " ExyliaLib does not bundle JDBC drivers: add it to the libraries section"
                    + " of the plugin.yml so the server downloads it once and every plugin"
                    + " shares the same copy.", missing);
        }
        if (settings.user() != null) {
            config.setUsername(settings.user());
        }
        if (settings.password() != null) {
            config.setPassword(settings.password());
        }
        config.setMaximumPoolSize(profile.maximumPoolSize());
        config.setMinimumIdle(profile.minimumIdle());
        config.setMaxLifetime(profile.maxLifetimeMillis());
        config.setKeepaliveTime(profile.keepaliveMillis());
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        // No connectionTestQuery on purpose. All four drivers are JDBC4, so
        // Hikari validates with Connection.isValid, which is a protocol-level
        // ping; setting a query would replace that with a full round trip of
        // "SELECT 1" per checkout and log a warning about it besides.
        config.setAutoCommit(true);

        try {
            HikariDataSource pool = new HikariDataSource(config);
            return new SqlBackend(asServed(dialect, pool), pool);
        } catch (RuntimeException failure) {
            // Hikari opens its first connection eagerly, so a wrong password or
            // an unreachable host arrives here rather than on the first query.
            // The URL is safe to name; the password is not, and is not in it.
            throw new IllegalStateException("Could not open a " + dialect.id()
                    + " pool for " + settings, failure);
        }
    }

    /**
     * The dialect the server on the other end actually speaks.
     *
     * <p>The configured {@code engine} names a driver and a URL, and an operator
     * writes {@code mysql} for a MariaDB server as often as not — the wire
     * protocol is compatible, connector-j connects, and every read works. What
     * does not work is one statement: MariaDB cannot <em>parse</em> MySQL
     * 8.0.20's {@code INSERT ... AS new} row alias, so every {@code saveAll}
     * fails with a syntax error at the first write, hours after the start that
     * would have been the place to notice.
     *
     * <p>So the pool is asked what it connected to. Only the SQL is switched;
     * the connection stays exactly as configured, driver and URL parameters
     * included, because those are connector-j's and are already working.
     *
     * <p>Both directions, and only between these two: {@code mariadb} pointed at
     * a real MySQL 8 would emit the deprecated {@code VALUES(col)} on every
     * write, which is a log file a day rather than an outage, but it is the same
     * mistake and the same fix.
     */
    private static Dialect asServed(Dialect dialect, HikariDataSource pool) {
        if (dialect != MySQLDialect.INSTANCE && dialect != MariaDBDialect.INSTANCE) {
            return dialect;
        }
        try (Connection connection = pool.getConnection()) {
            DatabaseMetaData server = connection.getMetaData();
            Dialect served = served(dialect, server.getDatabaseProductName() + " "
                    + server.getDatabaseProductVersion());
            if (served != dialect) {
                LOGGER.warning("The database is configured as " + dialect.id()
                        + " but the server reports itself as "
                        + server.getDatabaseProductVersion()
                        + ". Writing " + served.id() + " SQL instead, because the two"
                        + " engines do not share the syntax of an upsert. The connection"
                        + " itself is unchanged; set engine to " + served.id()
                        + " to remove this notice.");
            }
            return served;
        } catch (SQLException unreachable) {
            // Answering "no" here would swap a working dialect for a guess. The
            // pool has already opened a connection by this point, so this is a
            // server that went away in between, and the next query reports it.
            return dialect;
        }
    }

    /**
     * The dialect a server banner calls for, given the configured one.
     *
     * <p>Split from {@link #asServed} so the decision is testable without a
     * server of each engine to point at.
     *
     * @param dialect the configured dialect, already known to be one of the two
     * @param banner  the product name and version the driver reports
     */
    static Dialect served(Dialect dialect, String banner) {
        if (dialect != MySQLDialect.INSTANCE && dialect != MariaDBDialect.INSTANCE) {
            return dialect;
        }
        // The product name is the driver's word, not the server's: connector-j
        // says "MySQL" whatever it is talking to. The version banner is the
        // server's own and carries the fork's name.
        return banner.toLowerCase(java.util.Locale.ROOT).contains("mariadb")
                ? MariaDBDialect.INSTANCE
                : MySQLDialect.INSTANCE;
    }

    /** The dialect this backend speaks. */
    public @NotNull Dialect dialect() {
        return dialect;
    }

    /**
     * Everything about a model this engine cannot store correctly.
     *
     * <p>Asked before {@link #ensureTable}, and worth reporting even when it
     * comes back empty on the engine a developer happens to run: the limits are
     * the strictest of the four, so a model that passes here works on all of
     * them.
     *
     * @param model the record model
     * @return the problems, empty when there are none
     */
    public @NotNull List<String> validate(@NotNull EntityModel<?> model) {
        return dialect.validate(model);
    }

    /**
     * Creates or updates the table behind a model.
     *
     * <p>Idempotent: safe on every start, and safe when two servers start
     * against one database at the same moment.
     *
     * @param model the record model
     * @return what changed
     * @throws SQLException if a statement failed for a reason other than
     *                      "already there"
     */
    public @NotNull SchemaReport ensureTable(@NotNull EntityModel<?> model) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            return schema.ensure(connection, model);
        }
    }

    // ------------------------------------------------------------------ write

    /**
     * Writes one record, inserting or updating as needed.
     *
     * <p>Returns nothing on purpose. The affected-row count of an upsert cannot
     * be read as "inserted" or "updated": MySQL answers 1 for an insert and 2
     * for an update, and 0 when the update changed nothing at all, while
     * Postgres and H2 answer 1 for both. A caller that needs to know which
     * happened has to read the row first, and doing so on a server that also
     * writes it from another thread would be a guess anyway.
     *
     * @param model    the record model
     * @param instance the record
     * @param <T>      the record type
     * @throws SQLException if the write failed
     */
    public <T> void save(@NotNull EntityModel<T> model, @NotNull T instance) throws SQLException {
        String sql = statements.computeIfAbsent("save:" + model.type().getName(),
                key -> dialect.upsert(model, List.of(model.id().name())));
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRow(statement, model, model.values(instance));
            statement.executeUpdate();
        }
    }

    /**
     * Writes a record back to the row it already has.
     *
     * <p>Never creates one. A key that matches nothing updates nothing, which
     * is the honest answer for a design somebody deleted while it was open.
     *
     * @param model    the record model
     * @param instance the record, carrying the key it was stored under
     * @param <T>      the record type
     * @throws SQLException if the write failed
     * @since 1.43.0
     */
    public <T> void update(@NotNull EntityModel<T> model, @NotNull T instance) throws SQLException {
        String sql = statements.computeIfAbsent("update:" + model.type().getName(),
                key -> dialect.update(model));
        Object[] values = model.values(instance);
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (int column = 0; column < values.length; column++) {
                if (column == model.idIndex()) {
                    continue;
                }
                statement.setObject(index++, values[column]);
            }
            // The key is last, because it is the WHERE rather than a SET.
            statement.setObject(index, values[model.idIndex()]);
            statement.executeUpdate();
        }
    }

    /**
     * Writes many records in one batch.
     *
     * <p>One statement, one round trip per batch rather than per row: with
     * {@code rewriteBatchedStatements} on MySQL and
     * {@code reWriteBatchedInserts} on Postgres — both set by the dialect's
     * URL — the driver collapses the batch into a single multi-row statement,
     * measured at 8.8x on a thousand rows. Without those parameters a batch is
     * a loop with extra steps, which is why they are not optional.
     *
     * <p>Wrapped in one transaction, so a batch that fails halfway leaves the
     * table as it was rather than half-written.
     *
     * @param model     the record model
     * @param instances the records
     * @param <T>       the record type
     * @return how many rows were sent
     * @throws SQLException if the batch failed
     */
    public <T> int saveAll(@NotNull EntityModel<T> model, @NotNull Collection<T> instances) throws SQLException {
        if (instances.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>(instances.size());
        for (T instance : instances) {
            rows.add(model.values(instance));
        }
        return writeRows(model, rows);
    }

    /**
     * Writes rows already in storage form, as one batch, upserting by key.
     *
     * <p>The same statement, the same transaction and the same binding
     * {@link #saveAll} uses — it is literally that method's second half — with
     * the encoding step left out because these rows have already been through
     * it. That is what makes this the write half of a round trip that never
     * runs a codec: a row that came out of {@link #scan} goes back in here
     * unchanged, and an {@code ItemStack} column is text at both ends rather
     * than being deserialised into a Bukkit object and serialised again.
     *
     * <p>Every column is written, the key included, whether or not the engine
     * would have handed one out. There is deliberately no guard here against
     * writing an explicit generated id: the one that stops a plugin doing it by
     * accident lives on {@link net.exylia.lib.database.Repository}, at the
     * public surface, and this path is reached only from inside the library —
     * where writing the id an exported row already had is the entire point.
     * The counter is left where it is; see {@link #resequence}.
     *
     * @param model the record model
     * @param rows  the rows, each in {@link EntityModel#columns()} order
     * @return how many rows were sent
     * @throws SQLException if the batch failed
     * @since 1.36.0
     */
    public int writeRows(@NotNull EntityModel<?> model, @NotNull List<Object[]> rows)
            throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        String sql = statements.computeIfAbsent("save:" + model.type().getName(),
                key -> dialect.upsert(model, List.of(model.id().name())));
        try (Connection connection = pool.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bindRow(statement, model, row);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
                return rows.size();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Moves a generated key's counter past every key the table already holds.
     *
     * <p>Needed after rows arrive carrying their own ids, which is what
     * {@link #writeRows} does. H2 and Postgres do not advance the counter for a
     * row that supplied its key, so the next {@link #insert} would ask for 1 on
     * a table whose ids run to five hundred and fail on the primary key; MySQL
     * and MariaDB advance it themselves and take the statement as a
     * restatement of where they already are. Emitting it on all four is what
     * makes the four end in the same state.
     *
     * <p>A no-op for a model that brings its own key — there is no counter —
     * and for an empty table, where the counter is already correct wherever it
     * is.
     *
     * @param model the record model
     * @return the value the counter will next hand out, or {@code 0} when
     *         nothing was done
     * @throws SQLException if either statement failed
     * @since 1.36.0
     */
    public long resequence(@NotNull EntityModel<?> model) throws SQLException {
        if (!model.generatedId()) {
            return 0L;
        }
        long max;
        String maxSql = statements.computeIfAbsent("maxKey:" + model.type().getName(),
                key -> dialect.maxKey(model));
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(maxSql);
             ResultSet rows = statement.executeQuery()) {
            // MAX over an empty table is one row holding null, not no rows.
            max = rows.next() ? rows.getLong(1) : 0L;
        }
        if (max <= 0L) {
            return 0L;
        }
        long next = max + 1L;
        // Not cached by shape: the value is in the text, so caching it would
        // key a growing number of one-use strings on a map that is sized for a
        // handful of query shapes.
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(dialect.resequence(model, next));
        }
        return next;
    }

    /**
     * Inserts a row whose key the engine hands out, and returns that key.
     *
     * <p>Not a save. An upsert needs a key to resolve the conflict against, and
     * a record whose key does not exist yet has nothing to offer it — asking the
     * engine to merge on a placeholder zero would overwrite whichever row
     * happened to have that id.
     *
     * <p>The key comes back through {@code getGeneratedKeys}, which every engine
     * here supports on the same call that wrote the row. Reading it back with a
     * second statement — {@code SELECT MAX(id)} or {@code LAST_INSERT_ID()} on a
     * fresh connection — is the classic version of this bug: the pool hands out
     * a different connection, and on a table two servers write to the number
     * belongs to whoever inserted last.
     *
     * @param model    the record model, whose key must be generated
     * @param instance the record; its key value is ignored
     * @param <T>      the record type
     * @return the key the engine assigned
     * @throws SQLException if the insert failed or returned no key
     * @since 1.32.0
     */
    public <T> long insert(@NotNull EntityModel<T> model, @NotNull T instance) throws SQLException {
        String sql = statements.computeIfAbsent("insert:" + model.type().getName(),
                key -> dialect.insertGenerated(model));
        try (Connection connection = pool.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            List<ColumnModel> columns = model.insertColumns();
            Object[] values = model.insertValues(instance);
            for (int index = 0; index < columns.size(); index++) {
                bind(statement, index + 1, columns.get(index), values[index]);
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Insert into " + model.table()
                            + " returned no generated key.");
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * Deletes the row with a primary key.
     *
     * @param model the record model
     * @param id    the key, in its record form — it is encoded here, exactly as
     *              the column that stores it was, because a key encoded
     *              differently from its column matches nothing and reports it
     *              as "no such row"
     * @return whether a row was removed
     * @throws SQLException if the delete failed
     */
    public boolean delete(@NotNull EntityModel<?> model, @NotNull Object id) throws SQLException {
        String sql = statements.computeIfAbsent("delete:" + model.type().getName(),
                key -> dialect.delete(model, List.of(model.id().name())));
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, 1, model.id(), model.id().encode(id));
            return statement.executeUpdate() > 0;
        }
    }

    // ------------------------------------------------------------------- read

    /**
     * The record with a primary key, or {@code null} when there is none.
     *
     * @param model the record model
     * @param id    the key, in its record form
     * @param <T>   the record type
     * @return the record, or {@code null}
     * @throws SQLException if the read failed
     */
    public <T> @Nullable T find(@NotNull EntityModel<T> model, @NotNull Object id) throws SQLException {
        String sql = statements.computeIfAbsent("find:" + model.type().getName(),
                key -> dialect.select(model, List.of(model.id().name()), List.of(), 1, 0));
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, 1, model.id(), model.id().encode(id));
            // The limit is bound like everything else, so the text is the same
            // string for every key and the driver's cache actually hits.
            statement.setInt(2, 1);
            statement.setInt(3, 0);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readRow(rows, model) : null;
            }
        }
    }

    /**
     * Rows matching a filter, ordered and paged.
     *
     * <p>{@code LIMIT ? OFFSET ?} is the only pagination all four engines
     * accept: MySQL rejects the standard {@code OFFSET .. FETCH}, and Postgres
     * rejects MySQL's {@code LIMIT ?, ?}.
     *
     * @param model        the record model
     * @param whereColumns column names compared with {@code =}, joined by {@code AND}
     * @param whereValues  the values, in record form, one per column
     * @param order        sort columns, may be empty
     * @param limit        rows at most, {@code 0} or less for all of them
     * @param offset       rows skipped, requires a limit
     * @param <T>          the record type
     * @return the rows, in the engine's order when none was asked for
     * @throws SQLException             if the read failed
     * @throws IllegalArgumentException if the filter columns and values do not
     *                                  line up, or a column is not in the model
     */
    public <T> @NotNull List<T> select(@NotNull EntityModel<T> model,
                                       @NotNull List<String> whereColumns,
                                       @NotNull List<Object> whereValues,
                                       @NotNull List<Dialect.Sort> order,
                                       int limit,
                                       int offset) throws SQLException {
        if (whereColumns.size() != whereValues.size()) {
            throw new IllegalArgumentException("select on " + model.table() + " got "
                    + whereColumns.size() + " columns and " + whereValues.size() + " values");
        }
        List<ColumnModel> filter = resolve(model, whereColumns);
        List<Dialect.Sort> sorts = resolveSorts(model, order);
        String sql = statements.computeIfAbsent(
                "select:" + model.type().getName() + ":" + names(filter) + ":" + sorts + ":" + (limit > 0),
                key -> dialect.select(model, names(filter), sorts, limit, offset));
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int slot = bindWhere(statement, filter, whereValues);
            if (limit > 0) {
                statement.setInt(slot++, limit);
                statement.setInt(slot, offset);
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<T> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(readRow(rows, model));
                }
                return List.copyOf(found);
            }
        }
    }

    /**
     * Walks the whole table in primary-key order, in batches, without ever
     * holding more than one batch.
     *
     * <p>Rows are handed over in storage form: an {@code Object[]} in
     * {@link EntityModel#columns()} order, holding exactly what the driver
     * returned. No codec runs, so an {@code ItemStack} column is a Base64
     * string and never becomes a Bukkit object — which is what lets a whole
     * table be walked on a background thread with no game types involved, and
     * what makes the round trip through {@link #writeRows} exact rather than
     * merely equivalent.
     *
     * <h2>Keyset, not offset</h2>
     * Each batch resumes strictly after the last key of the previous one, which
     * is an index seek and a total order: O(n) for the whole table and every
     * row seen exactly once. {@code LIMIT ? OFFSET ?} would be O(n²), and
     * without an {@code ORDER BY} — which ExyliaCommons omitted — the engine may
     * order the rows differently per page, so rows silently appear twice or not
     * at all.
     *
     * <h2>Memory</h2>
     * One batch is alive at a time and the caller's block is called with it
     * before the next is read. A table of four hundred thousand rows costs the
     * same as one of four hundred, which is the whole reason this exists rather
     * than {@code select} with no limit.
     *
     * <p>Every batch is bounded by its {@code LIMIT}, so the connection goes
     * back to the pool between batches and nothing here touches
     * {@code autoCommit} or {@code setFetchSize} — a cursor held open across a
     * whole table would keep a pooled connection, and on Postgres a transaction,
     * for the length of the walk.
     *
     * @param model     the record model
     * @param batchSize rows per batch, at least one
     * @param block     called once per batch, on this thread, before the next
     *                  batch is read; whatever it throws propagates out of here
     *                  and ends the scan
     * @return how many rows were handed over in total
     * @throws SQLException             if a read failed
     * @throws IllegalArgumentException if the batch size is not positive
     * @since 1.36.0
     */
    public long scan(@NotNull EntityModel<?> model,
                     int batchSize,
                     @NotNull Consumer<List<Object[]>> block) throws SQLException {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("A scan of " + model.table()
                    + " needs a batch size of at least one row, not " + batchSize
                    + ". The batch is what bounds the memory the walk uses.");
        }
        String first = statements.computeIfAbsent("scan:" + model.type().getName(),
                key -> dialect.scan(model, false));
        String next = statements.computeIfAbsent("scanAfter:" + model.type().getName(),
                key -> dialect.scan(model, true));
        int keyIndex = model.idIndex();
        long total = 0L;
        Object cursor = null;
        while (true) {
            List<Object[]> batch = new ArrayList<>(batchSize);
            try (Connection connection = pool.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement(cursor == null ? first : next)) {
                int slot = 1;
                if (cursor != null) {
                    // Already in storage form: it came out of the previous
                    // batch, which is what the column holds. Encoding it again
                    // would hand a UUID column's codec a String.
                    bind(statement, slot++, model.id(), cursor);
                }
                statement.setInt(slot, batchSize);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        batch.add(readValues(rows, model));
                    }
                }
            }
            if (batch.isEmpty()) {
                return total;
            }
            total += batch.size();
            cursor = batch.get(batch.size() - 1)[keyIndex];
            // After the cursor is taken and before the next read: the block may
            // keep, wrap or discard the list, and it must not be able to affect
            // where the walk resumes.
            block.accept(batch);
            if (batch.size() < batchSize) {
                // A short batch is the end of the table. Asking again would be
                // one round trip per scan to be told the same thing.
                return total;
            }
        }
    }

    /**
     * How many rows match a filter.
     *
     * @param model        the record model
     * @param whereColumns column names compared with {@code =}, may be empty
     * @param whereValues  the values, in record form, one per column
     * @return the count
     * @throws SQLException if the read failed
     */
    public long count(@NotNull EntityModel<?> model,
                      @NotNull List<String> whereColumns,
                      @NotNull List<Object> whereValues) throws SQLException {
        if (whereColumns.size() != whereValues.size()) {
            throw new IllegalArgumentException("count on " + model.table() + " got "
                    + whereColumns.size() + " columns and " + whereValues.size() + " values");
        }
        List<ColumnModel> filter = resolve(model, whereColumns);
        String sql = statements.computeIfAbsent(
                "count:" + model.type().getName() + ":" + names(filter),
                key -> dialect.count(model, names(filter)));
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindWhere(statement, filter, whereValues);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    // ---------------------------------------------------------------- binding

    private static int bindWhere(@NotNull PreparedStatement statement,
                                 @NotNull List<ColumnModel> filter,
                                 @NotNull List<Object> whereValues) throws SQLException {
        int slot = 1;
        for (int index = 0; index < filter.size(); index++) {
            ColumnModel column = filter.get(index);
            // Encoded through the column that stores it, never handed over
            // raw: a UUID filter compared as a UUID object against a VARCHAR
            // column matches nothing, and reports it as an empty result rather
            // than as an error.
            bind(statement, slot++, column, column.encode(whereValues.get(index)));
        }
        return slot;
    }

    /**
     * Resolves the names a caller filtered on to real columns, before any SQL
     * is built from them.
     *
     * <p>Two things depend on doing it here. A caller thinks in record
     * components — {@code killStreak} — while the table has {@code kill_streak},
     * so the name has to be translated or the statement names a column that
     * does not exist. And a name belonging to neither has to fail as an
     * {@link IllegalArgumentException} naming the model, rather than as a
     * driver's syntax error from four frames deeper.
     */
    private static @NotNull List<ColumnModel> resolve(@NotNull EntityModel<?> model,
                                                      @NotNull List<String> names) {
        List<ColumnModel> resolved = new ArrayList<>(names.size());
        for (String name : names) {
            resolved.add(columnOf(model, name));
        }
        return resolved;
    }

    /** The same translation for sort columns, which name a column just as a filter does. */
    private static @NotNull List<Dialect.Sort> resolveSorts(@NotNull EntityModel<?> model,
                                                            @NotNull List<Dialect.Sort> order) {
        List<Dialect.Sort> resolved = new ArrayList<>(order.size());
        for (Dialect.Sort sort : order) {
            resolved.add(new Dialect.Sort(columnOf(model, sort.column()).name(), sort.descending()));
        }
        return resolved;
    }

    private static @NotNull List<String> names(@NotNull List<ColumnModel> columns) {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnModel column : columns) {
            names.add(column.name());
        }
        return names;
    }

    private static @NotNull ColumnModel columnOf(@NotNull EntityModel<?> model, @NotNull String name) {
        ColumnModel column = model.column(name);
        if (column == null) {
            column = model.byComponent(name);
        }
        if (column == null) {
            throw new IllegalArgumentException("No column or component '" + name + "' on "
                    + model.type().getSimpleName() + " (table " + model.table() + ")");
        }
        return column;
    }

    private static void bindRow(@NotNull PreparedStatement statement,
                                @NotNull EntityModel<?> model,
                                @NotNull Object[] values) throws SQLException {
        List<ColumnModel> columns = model.columns();
        for (int index = 0; index < columns.size(); index++) {
            bind(statement, index + 1, columns.get(index), values[index]);
        }
    }

    /**
     * Binds one already-encoded value.
     *
     * <p>A null is bound with an explicit SQL type rather than through
     * {@code setObject(i, null)}. Postgres refuses the latter with
     * "can't infer the SQL type to use for an instance of null" whenever the
     * parameter's type is not obvious from context, which is exactly what an
     * upsert's {@code SET} clause looks like — and it is a runtime failure on
     * one engine only, so it survives every test run against another.
     */
    private static void bind(@NotNull PreparedStatement statement,
                             int slot,
                             @NotNull ColumnModel column,
                             @Nullable Object value) throws SQLException {
        if (value == null) {
            statement.setNull(slot, sqlTypeOf(column));
            return;
        }
        // setString rather than setObject for text: a codec-encoded value is a
        // String and every driver has a direct path for it, while setObject
        // dispatches on the runtime class first.
        if (value instanceof String text) {
            statement.setString(slot, text);
            return;
        }
        statement.setObject(slot, value);
    }

    private static int sqlTypeOf(@NotNull ColumnModel column) {
        Class<?> stored = column.storedType();
        if (stored == String.class) {
            return Types.VARCHAR;
        }
        if (stored == int.class || stored == Integer.class) {
            return Types.INTEGER;
        }
        if (stored == long.class || stored == Long.class) {
            return Types.BIGINT;
        }
        if (stored == short.class || stored == Short.class) {
            return Types.SMALLINT;
        }
        if (stored == byte.class || stored == Byte.class) {
            return Types.TINYINT;
        }
        if (stored == double.class || stored == Double.class) {
            return Types.DOUBLE;
        }
        if (stored == float.class || stored == Float.class) {
            return Types.REAL;
        }
        if (stored == boolean.class || stored == Boolean.class) {
            return Types.BOOLEAN;
        }
        if (stored == BigDecimal.class) {
            return Types.DECIMAL;
        }
        return Types.OTHER;
    }

    /**
     * Reads one row into a record.
     *
     * <p>By position, not by label. The {@code SELECT} listed the columns in
     * model order, so the positions are known, and a label lookup is a hash of
     * the column name per column per row — on a leaderboard of ten thousand
     * rows that is the difference between a hundred thousand hashes and none.
     *
     * <p>Text is read with {@code getString} rather than {@code getObject}.
     * A large text column is a different type on each engine and some of them
     * hand {@code getObject} a {@link java.sql.Clob} wrapper rather than a
     * {@code String} — verified against H2, where a column declared
     * {@code CLOB} comes back as {@code org.h2.jdbc.JdbcClob}, whose
     * {@code toString} is an object identity and not the stored text. A
     * serialised inventory read that way is silently replaced by
     * {@code org.h2.jdbc.JdbcClob@1a2b}, and the row it was decoded into is
     * unreadable. {@code getString} is defined to materialise the value on
     * every driver.
     */
    private static <T> @NotNull T readRow(@NotNull ResultSet rows, @NotNull EntityModel<T> model)
            throws SQLException {
        return model.read(readValues(rows, model));
    }

    /**
     * One row as the driver returned it, in column order and nothing more.
     *
     * <p>The half of {@link #readRow} that stops short of the record. A scan
     * wants exactly this and not the record: decoding would run every codec to
     * produce Bukkit objects that the export is about to encode back into the
     * same strings, and would need a server to do it.
     */
    private static @NotNull Object[] readValues(@NotNull ResultSet rows,
                                                @NotNull EntityModel<?> model) throws SQLException {
        List<ColumnModel> columns = model.columns();
        Object[] values = new Object[columns.size()];
        for (int index = 0; index < columns.size(); index++) {
            ColumnModel column = columns.get(index);
            values[index] = column.storedType() == String.class
                    ? rows.getString(index + 1)
                    : rows.getObject(index + 1);
        }
        return values;
    }

    /**
     * Closes the pool.
     *
     * <p>Every connection is closed, in-flight or not. Called when the library
     * disables: a pool that outlives its plugin holds threads and sockets that
     * nothing will ever close.
     */
    @Override
    public void close() {
        pool.close();
    }

    /** Whether the pool is still usable. */
    public boolean isOpen() {
        return pool.isRunning();
    }

    @Override
    public String toString() {
        return "SqlBackend[" + dialect.id() + " " + pool.getPoolName() + "]";
    }
}
