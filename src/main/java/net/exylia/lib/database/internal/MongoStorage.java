package net.exylia.lib.database.internal;

import net.exylia.lib.database.DatabaseException;
import net.exylia.lib.database.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link Storage} over a {@link MongoBackend}: MongoDB.
 *
 * <p>The Mongo half of what {@link SqlStorage} does for the four SQL engines,
 * and deliberately the same two jobs. It moves every call onto a background
 * executor, so the thread that asked — very often the main one — is never the
 * thread that waits for a socket. And it names the collection and the operation
 * in any failure, because a driver's message on its own says nothing about
 * which plugin, which record or which query produced it.
 *
 * <p>It contains no Mongo. Documents, filters and indexes are
 * {@link MongoDocuments}' business and the round trips are
 * {@link MongoBackend}'s; this class names neither {@code com.mongodb} nor
 * {@code org.bson}, which is what lets a server that never configures Mongo
 * avoid loading the driver at all.
 *
 * <h2>Where it cannot behave like SQL</h2>
 * Three of these methods differ observably from their SQL counterparts, and the
 * differences are documented on {@link MongoBackend} rather than smoothed over
 * here: {@link #saveAll} is not a transaction, {@link #deleteWhere} with a limit
 * is not atomic, and {@link #prepare} never reports an added column because a
 * document has no schema to add one to. Pretending otherwise would mean
 * emulating SQL semantics Mongo does not have, at a cost the caller cannot see.
 *
 * <h2>Threads</h2>
 * Safe from any thread. The backend is, the executor is, and this class holds
 * no mutable state of its own. It creates no threads: the executor is handed
 * in, which is what keeps every scheduled task under the library's own
 * {@code Tasks} and cancellable when a plugin disables.
 *
 * @see Storage
 * @see MongoBackend
 * @since 1.24.0
 */
public final class MongoStorage implements Storage {

    private final MongoBackend backend;
    private final Executor executor;
    private final Consumer<String> warnings;

    /**
     * Wraps an open backend.
     *
     * @param backend  the client, already open
     * @param executor where the work runs — a background one, always
     * @param warnings where model problems are reported; see {@link #prepare}
     */
    public MongoStorage(@NotNull MongoBackend backend,
                        @NotNull Executor executor,
                        @NotNull Consumer<String> warnings) {
        this.backend = backend;
        this.executor = executor;
        this.warnings = warnings;
    }

    /** The backend underneath, for the library's own diagnostics. */
    public @NotNull MongoBackend backend() {
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
        // lands next to whatever the plugin was doing. Mongo has the same
        // leftmost-prefix rule for a compound index as the four SQL engines, so
        // the same coverage answers both.
        IndexCoverage.check(model, columns, order, warnings);
        // An offset with no limit is honoured here, unlike on SQL, where MySQL
        // has no bare OFFSET and the dialect refuses it. Mongo's skip needs no
        // limit to go with it.
        return async(model, "select", () -> backend.select(model, columns, values, sorts, limit, offset));
    }

    @Override
    public @NotNull CompletableFuture<Long> count(@NotNull EntityModel<?> model,
                                                  @NotNull List<String> whereColumns,
                                                  @NotNull List<Object> whereValues) {
        List<String> columns = List.copyOf(whereColumns);
        List<Object> values = new ArrayList<>(whereValues);
        IndexCoverage.check(model, columns, List.of(), warnings);
        return async(model, "count", () -> backend.count(model, columns, values));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> exists(@NotNull EntityModel<?> model,
                                                      @NotNull Object id) {
        // A capped find projected down to _id, not a read of the document. The
        // document may carry a serialised inventory, and asking "is this player
        // known" should not pull a megabyte over the wire and Base64-decode it
        // to answer yes.
        List<String> columns = List.of(model.id().name());
        List<Object> values = List.of(id);
        return async(model, "exists", () -> backend.exists(model, columns, values));
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
    public <T> @NotNull CompletableFuture<Void> saveAll(@NotNull EntityModel<T> model,
                                                        @NotNull Collection<T> records) {
        if (records.isEmpty()) {
            // No task, no round trip. A save-on-quit sweep that finds nothing
            // to write runs constantly on a busy server.
            return CompletableFuture.completedFuture(null);
        }
        List<T> copy = List.copyOf(records);
        return async(model, "saveAll", () -> {
            backend.saveAll(model, copy);
            return null;
        });
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
        // One round trip when there is no limit, and two when there is: Mongo
        // has no DELETE ... LIMIT. See MongoBackend#deleteWhere, which is where
        // that trade and its consequences are written down.
        return async(model, "delete", () -> {
            long removed = backend.deleteWhere(model, columns, values, limit);
            // Saturating rather than wrapping. A collection with more than two
            // billion matching documents is not something any plugin here has,
            // but a negative count returned for one would be a number nobody
            // could interpret.
            return (int) Math.min(removed, Integer.MAX_VALUE);
        });
    }

    // ------------------------------------------------------------------- DDL

    @Override
    public @NotNull CompletableFuture<SchemaReport> prepare(@NotNull EntityModel<?> model) {
        return async(model, "prepare", () -> {
            // A far shorter list than the SQL side's, because most of what a
            // dialect checks does not exist here: no column type to map, no
            // length to respect, no row-size ceiling. What is left is field
            // names, where Mongo is permissive about what it accepts and
            // unforgiving about what it then means.
            for (String problem : backend.validate(model)) {
                warnings.accept(model.type().getSimpleName() + ": " + problem);
            }
            // Computed here so that the whole cost of the missing-index
            // diagnostic is paid once at registration and never on a query.
            IndexCoverage.of(model);
            return backend.prepare(model);
        });
    }

    @Override
    public void close() {
        backend.close();
    }

    // ------------------------------------------------------------- machinery

    /**
     * Translates a {@link Query.Sort} into the one the backends speak.
     *
     * <p>They are opposites, not synonyms: {@code Query.Sort} carries
     * {@code ascending} because that is what reads well at a call site, and
     * {@code Dialect.Sort} carries {@code descending} because that is the
     * keyword a statement emits. Confusing them compiles, runs, and hands a
     * leaderboard back with the worst player at the top.
     *
     * <p>{@code Dialect.Sort} is reused rather than given a Mongo twin so that
     * a sort means one thing across the module. It is a column and a direction;
     * nothing about it is SQL.
     *
     * <p>Package-private rather than private: it is a test seam. An inverted
     * sort is invisible to the compiler and to every test that does not check
     * the direction itself, and the only symptom is a leaderboard nobody
     * notices is upside down until a player does.
     */
    static @NotNull List<Dialect.Sort> sorts(@NotNull List<Query.Sort> order) {
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
                                                    @NotNull Supplier<R> work) {
        CompletableFuture<R> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(work.get());
                } catch (DatabaseException already) {
                    future.completeExceptionally(already);
                } catch (Throwable failure) {
                    // The driver's exceptions are unchecked, unlike JDBC's, so
                    // there is no separate catch for "a database problem": both
                    // a refused write and a record whose compact constructor
                    // rejected a stored document arrive here. Both must reach
                    // the caller — an exception thrown on a background thread
                    // and not attached to the future disappears into the
                    // scheduler's log with no indication of who asked for it.
                    future.completeExceptionally(new DatabaseException(
                            "Could not " + operation + " on " + model.table() + " ("
                                    + model.type().getSimpleName() + "): " + failure.getMessage(),
                            failure));
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

    @Override
    public String toString() {
        return "MongoStorage[" + backend + ']';
    }
}
