package net.exylia.lib.database.internal;

import net.exylia.lib.database.DatabaseException;
import net.exylia.lib.database.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * {@link Storage} over a {@link SqlBackend}: the four SQL engines.
 *
 * <p>Two jobs, and deliberately nothing else. It moves every call onto a
 * background executor, so the thread that asked — very often the main one — is
 * never the thread that waits for a socket. And it turns a driver's
 * {@link SQLException} into a {@link DatabaseException} whose message names the
 * table and the operation, because a vendor error code on its own tells the
 * person reading the console nothing about which plugin, which record or which
 * query produced it.
 *
 * <p>It contains no SQL. Statements are the {@link Dialect}'s business and
 * binding is {@link SqlBackend}'s; duplicating either here would mean two places
 * that have to agree about what a column is called.
 *
 * <h2>Nothing is ever swallowed</h2>
 * Every failure completes its future exceptionally. A write that silently did
 * nothing is the worst outcome available: the plugin tells the player their
 * purchase went through, the row was never written, and nobody finds out until
 * they log back in.
 *
 * <h2>Threads</h2>
 * Safe from any thread. The backend is, the executor is, and this class holds
 * no mutable state of its own.
 *
 * @see Storage
 * @since 1.24.0
 */
public final class SqlStorage implements Storage {

    private final SqlBackend backend;
    private final Executor executor;
    private final Consumer<String> warnings;

    /**
     * Wraps an open backend.
     *
     * @param backend  the pool and its statements, already open
     * @param executor where the work runs — a background one, always
     * @param warnings where model problems the engine cannot enforce are
     *                 reported; see {@link #prepare}
     */
    public SqlStorage(@NotNull SqlBackend backend,
                      @NotNull Executor executor,
                      @NotNull Consumer<String> warnings) {
        this.backend = backend;
        this.executor = executor;
        this.warnings = warnings;
    }

    /** The backend underneath, for the library's own diagnostics. */
    public @NotNull SqlBackend backend() {
        return backend;
    }

    // ------------------------------------------------------------------ read

    @Override
    public <T> @NotNull CompletableFuture<@Nullable T> find(@NotNull EntityModel<T> model,
                                                            @NotNull Object id) {
        return async(model, "find", () -> backend.find(model, id));
    }

    @Override
    public <T> @NotNull CompletableFuture<List<T>> select(@NotNull EntityModel<T> model,
                                                          @NotNull List<String> whereColumns,
                                                          @NotNull List<Object> whereValues,
                                                          @NotNull List<Query.Sort> order,
                                                          int limit,
                                                          int offset) {
        // Copied before leaving the caller's thread. A Query is mutable by
        // design — where().orderBy().limit() is one object being built — and
        // the future may still be reading these lists when the caller reuses
        // the query for the next page.
        List<String> columns = List.copyOf(whereColumns);
        List<Object> values = new ArrayList<>(whereValues);
        List<Dialect.Sort> sorts = sorts(order);
        // Checked on the calling thread, before the work is queued, so the line
        // lands next to whatever the plugin was doing rather than on a
        // background thread minutes later. It is a set lookup on a short string:
        // the index prefixes were computed once, at registration.
        IndexCoverage.check(model, columns, order, warnings);
        // An offset without a limit has no portable SQL: MySQL has no bare
        // OFFSET, and the usual workaround asks for 18446744073709551615 rows.
        // Refused by the dialect, which is where the explanation lives.
        return async(model, "select", () -> backend.select(model, columns, values, sorts, limit, offset));
    }

    @Override
    public @NotNull CompletableFuture<Long> count(@NotNull EntityModel<?> model,
                                                  @NotNull List<String> whereColumns,
                                                  @NotNull List<Object> whereValues) {
        List<String> columns = List.copyOf(whereColumns);
        List<Object> values = new ArrayList<>(whereValues);
        // A filtered count is a scan of everything matching the filter unless an
        // index covers it, which is the same cost as reading the rows and the
        // same warning.
        IndexCoverage.check(model, columns, List.of(), warnings);
        return async(model, "count", () -> backend.count(model, columns, values));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> exists(@NotNull EntityModel<?> model,
                                                      @NotNull Object id) {
        // A count on the primary key, not a find. The row may carry a
        // serialised inventory, and asking "is this player known" should not
        // pull a megabyte over the wire and Base64-decode it to answer yes.
        List<String> columns = List.of(model.id().name());
        List<Object> values = List.of(id);
        return async(model, "exists", () -> backend.count(model, columns, values) > 0L);
    }

    // ----------------------------------------------------------------- write

    @Override
    public <T> @NotNull CompletableFuture<Void> save(@NotNull EntityModel<T> model,
                                                     @NotNull T record) {
        return async(model, "save", () -> {
            backend.save(model, record);
            return null;
        });
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> update(@NotNull EntityModel<T> model,
                                                       @NotNull T record) {
        return async(model, "update", () -> {
            backend.update(model, record);
            return null;
        });
    }

    @Override
    public <T> @NotNull CompletableFuture<Long> insert(@NotNull EntityModel<T> model,
                                                       @NotNull T record) {
        return async(model, "insert", () -> backend.insert(model, record));
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> saveAll(@NotNull EntityModel<T> model,
                                                        @NotNull Collection<T> records) {
        if (records.isEmpty()) {
            // No task, no connection, no round trip. A save-on-quit sweep that
            // finds nothing to write runs constantly on a busy server.
            return CompletableFuture.completedFuture(null);
        }
        List<T> copy = List.copyOf(records);
        return async(model, "saveAll", () -> {
            backend.saveAll(model, copy);
            return null;
        });
    }

    @Override
    public <T> @NotNull CompletableFuture<Long> scan(@NotNull EntityModel<T> model,
                                                     int batchSize,
                                                     @NotNull Consumer<List<Object[]>> block) {
        if (batchSize <= 0) {
            // Thrown here rather than completed exceptionally: it is a bug at
            // the call site, not a condition to recover from, and the module
            // treats a bad argument that way everywhere. Checked before the
            // work is queued so the stack trace names the caller.
            throw new IllegalArgumentException("A scan of " + model.table()
                    + " needs a batch size of at least one row, not " + batchSize
                    + ". The batch is what bounds the memory the walk uses.");
        }
        // The block runs on the executor, inside this call: whatever it throws
        // comes out of backend.scan and lands in async's catch, which fails the
        // future and reads no further batch.
        return async(model, "scan", () -> backend.scan(model, batchSize, block));
    }

    @Override
    public @NotNull CompletableFuture<Integer> writeRows(@NotNull EntityModel<?> model,
                                                         @NotNull List<Object[]> rows) {
        if (rows.isEmpty()) {
            // No task, no connection, no round trip — the same as saveAll.
            return CompletableFuture.completedFuture(0);
        }
        List<Object[]> copy = List.copyOf(rows);
        return async(model, "writeRows", () -> backend.writeRows(model, copy));
    }

    @Override
    public @NotNull CompletableFuture<Long> resequence(@NotNull EntityModel<?> model) {
        return async(model, "resequence", () -> backend.resequence(model));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull EntityModel<?> model,
                                                      @NotNull Object id) {
        return async(model, "delete", () -> backend.delete(model, id));
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteWhere(@NotNull EntityModel<?> model,
                                                           @NotNull List<String> whereColumns,
                                                           @NotNull List<Object> whereValues,
                                                           int limit) {
        List<String> columns = List.copyOf(whereColumns);
        List<Object> values = new ArrayList<>(whereValues);
        // A filtered delete finds its rows the same way a filtered select does,
        // so an uncovered filter is the same full scan and the same warning.
        IndexCoverage.check(model, columns, List.of(), warnings);
        return async(model, "delete", () -> deleteMatching(model, columns, values, limit));
    }

    @Override
    public @NotNull CompletableFuture<Long> deleteAll(@NotNull EntityModel<?> model) {
        return async(model, "delete", () -> backend.deleteAll(model));
    }

    /**
     * Removes the rows matching a filter, one key at a time.
     *
     * <p>Not the statement it wants to be. {@link SqlBackend} exposes a delete
     * by primary key and nothing wider, and it owns the pool, so a single
     * {@code DELETE ... WHERE} cannot be issued from here — the matching rows
     * are read first and then removed by key. That costs a read the ideal
     * version does not, and on a table with a serialised inventory column the
     * read is the expensive half.
     *
     * <p>It is still correct, and correct in the two ways that matter: the
     * count returned is rows actually removed, and the whole thing runs on one
     * background task rather than handing the caller a partially applied
     * result. When the backend grows a filtered delete this method becomes one
     * line, and nothing above it changes.
     *
     * <p>The key is decoded back to record form before being handed to
     * {@link SqlBackend#delete}, which encodes it again through the column that
     * stores it. Passing the stored form straight through would encode it
     * twice — a {@code UUID} column's codec handed a {@code String} — and the
     * delete would match nothing while reporting success.
     */
    private <T> int deleteMatching(EntityModel<T> model,
                                   List<String> columns,
                                   List<Object> values,
                                   int limit) throws SQLException {
        List<T> doomed = backend.select(model, columns, values, List.of(), limit, 0);
        int removed = 0;
        for (T row : doomed) {
            Object id = model.id().decode(model.idOf(row));
            if (id != null && backend.delete(model, id)) {
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ DDL

    @Override
    public @NotNull CompletableFuture<SchemaReport> prepare(@NotNull EntityModel<?> model) {
        return async(model, "prepare", () -> {
            // Reported before the table is touched, and reported even when the
            // engine a developer happens to run is not the one that minds. The
            // limits checked are the strictest of the four, so a model that
            // passes here stores correctly on all of them — and the one that
            // matters most is silent by nature: MariaDB shortens an index on a
            // wide text column to a 768-character prefix and says nothing, at
            // which point a column declared unique stops enforcing uniqueness.
            for (String problem : backend.validate(model)) {
                warnings.accept(model.type().getSimpleName() + ": " + problem);
            }
            // Computed here so that the whole cost of the missing-index
            // diagnostic — walking the indexes, building their prefixes — is
            // paid once at registration and never on a query.
            IndexCoverage.of(model);
            return backend.ensureTable(model);
        });
    }

    @Override
    public void close() {
        backend.close();
    }

    // ------------------------------------------------------------- machinery

    /**
     * Translates a {@link Query.Sort} into the one a dialect speaks.
     *
     * <p>They are opposites, not synonyms: {@code Query.Sort} carries
     * {@code ascending} because that is what reads well at a call site, and
     * {@code Dialect.Sort} carries {@code descending} because that is the
     * keyword a statement emits. Confusing them compiles, runs, and hands a
     * leaderboard back with the worst player at the top.
     */
    private static @NotNull List<Dialect.Sort> sorts(@NotNull List<Query.Sort> order) {
        if (order.isEmpty()) {
            return List.of();
        }
        List<Dialect.Sort> converted = new ArrayList<>(order.size());
        for (Query.Sort sort : order) {
            converted.add(new Dialect.Sort(sort.column(), !sort.ascending()));
        }
        return List.copyOf(converted);
    }

    /**
     * Runs one blocking operation on the executor and completes a future with
     * whatever it produced.
     *
     * <p>Hand-rolled rather than {@link CompletableFuture#supplyAsync}, for the
     * one case that method gets wrong here: a plugin's scheduler refuses work
     * once the plugin is disabled, and {@code supplyAsync} lets that rejection
     * escape from the <em>caller's</em> thread as a thrown exception. A caller
     * that correctly handles every failure through the future would then be
     * killed by the one failure that arrived by another route — during
     * shutdown, which is exactly when a plugin is flushing player data.
     *
     * @param model     the model, named in any failure message
     * @param operation what was being attempted, named in any failure message
     * @param work      the blocking call
     * @param <R>       what it produces
     * @return a future for the result
     */
    private <R> @NotNull CompletableFuture<R> async(@NotNull EntityModel<?> model,
                                                    @NotNull String operation,
                                                    @NotNull SqlWork<R> work) {
        CompletableFuture<R> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(work.run());
                } catch (SQLException failure) {
                    future.completeExceptionally(new DatabaseException(
                            "Could not " + operation + " on " + model.table() + " ("
                                    + model.type().getSimpleName() + "): " + failure.getMessage(),
                            failure));
                } catch (Throwable failure) {
                    // An IllegalArgumentException from an unknown filter column,
                    // an IllegalStateException from a record whose compact
                    // constructor rejected a stored row. Both are bugs in code
                    // rather than in the database, and both must still reach
                    // the caller: an exception thrown on a background thread
                    // and not attached to the future disappears into the
                    // scheduler's log with no indication of who asked for it.
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            future.completeExceptionally(new DatabaseException(
                    "Could not " + operation + " on " + model.table()
                            + ": the database work could not be scheduled, which normally means"
                            + " the plugin that asked is being disabled.", rejected));
        }
        return future;
    }

    /** One blocking backend call, allowed to fail the way a driver fails. */
    @FunctionalInterface
    private interface SqlWork<R> {

        @Nullable R run() throws SQLException;
    }

    @Override
    public String toString() {
        return "SqlStorage[" + backend + ']';
    }
}
