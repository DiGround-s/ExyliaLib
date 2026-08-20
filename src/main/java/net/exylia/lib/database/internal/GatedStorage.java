package net.exylia.lib.database.internal;

import net.exylia.lib.database.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link Storage} that holds every call back until the store behind it is
 * ready to take it.
 *
 * <p>Registering a repository is allowed to be instant and is not allowed to
 * block: it happens in {@code onEnable}, on the main thread, and behind it are
 * two round trips to a machine that may not be this one — opening the pool and
 * creating the table. So both are started in the background and the repository
 * is handed over immediately, which leaves a window in which a plugin can save
 * a record before the table it goes in exists.
 *
 * <p>This class is that window's answer. It holds a future that completes with
 * the real storage once the connection is open and the table is there, and
 * chains every operation onto it rather than racing it. A write issued in the
 * same tick as the registration therefore lands after the {@code CREATE TABLE}
 * and succeeds. The alternative — documenting that a caller must wait first —
 * is a rule that holds until the one plugin that forgets ships a
 * {@code table not found} to a live server.
 *
 * <p>Chaining costs nothing once the future is done: a completed
 * {@link CompletableFuture} runs the next stage inline on the calling thread,
 * and all that stage does is hand the call to the delegate, which is what
 * actually moves the work off the thread. No second dispatch, no second hop.
 *
 * <p>When preparation fails, every operation fails with the same cause. That is
 * the honest outcome: a repository whose table could not be created cannot
 * store anything, and answering a read with an empty list would be
 * indistinguishable from a database that is merely new.
 *
 * @since 1.24.0
 */
public final class GatedStorage implements Storage {

    private final CompletableFuture<Storage> ready;
    private final OperationGate operations;

    /**
     * A view that waits for a store to become usable.
     *
     * @param ready completes with the storage once the connection is open and
     *              the table exists, or fails with why it never will
     */
    public GatedStorage(@NotNull CompletableFuture<Storage> ready) {
        this(ready, Supplier::get);
    }

    /**
     * A view that waits for a store and registers its work with the target that
     * owns the eventual connection.
     */
    public GatedStorage(@NotNull CompletableFuture<Storage> ready, @NotNull OperationGate operations) {
        this.ready = ready;
        this.operations = operations;
    }

    @Override
    public <T> @NotNull CompletableFuture<@Nullable T> find(@NotNull EntityModel<T> model,
                                                            @NotNull Object id) {
        return after(storage -> storage.find(model, id));
    }

    @Override
    public <T> @NotNull CompletableFuture<List<T>> select(@NotNull EntityModel<T> model,
                                                          @NotNull List<String> whereColumns,
                                                          @NotNull List<Object> whereValues,
                                                          @NotNull List<Query.Sort> order,
                                                          int limit,
                                                          int offset) {
        // Copied here rather than inside the lambda. A Query is mutable by
        // design — where().orderBy().limit() is one object being built — and
        // between this call and the gate opening, the caller may well have
        // reused it to ask for the next page.
        List<String> columns = List.copyOf(whereColumns);
        List<Object> values = new ArrayList<>(whereValues);
        List<Query.Sort> sorts = List.copyOf(order);
        return after(storage -> storage.select(model, columns, values, sorts, limit, offset));
    }

    @Override
    public @NotNull CompletableFuture<Long> count(@NotNull EntityModel<?> model,
                                                  @NotNull List<String> whereColumns,
                                                  @NotNull List<Object> whereValues) {
        List<String> columns = List.copyOf(whereColumns);
        List<Object> values = new ArrayList<>(whereValues);
        return after(storage -> storage.count(model, columns, values));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> exists(@NotNull EntityModel<?> model,
                                                      @NotNull Object id) {
        return after(storage -> storage.exists(model, id));
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> save(@NotNull EntityModel<T> model,
                                                     @NotNull T record) {
        return after(storage -> storage.save(model, record));
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> update(@NotNull EntityModel<T> model,
                                                       @NotNull T record) {
        return after(storage -> storage.update(model, record));
    }

    @Override
    public <T> @NotNull CompletableFuture<Long> insert(@NotNull EntityModel<T> model,
                                                       @NotNull T record) {
        return after(storage -> storage.insert(model, record));
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> saveAll(@NotNull EntityModel<T> model,
                                                        @NotNull Collection<T> records) {
        List<T> copy = List.copyOf(records);
        return after(storage -> storage.saveAll(model, copy));
    }

    @Override
    public <T> @NotNull CompletableFuture<Long> scan(@NotNull EntityModel<T> model,
                                                     int batchSize,
                                                     @NotNull Consumer<List<Object[]>> block) {
        if (batchSize <= 0) {
            // Checked here as well as in the delegate. A bad argument must
            // reach the caller as a throw, and behind the gate it would arrive
            // as a failed future minutes later instead.
            throw new IllegalArgumentException("A scan of " + model.table()
                    + " needs a batch size of at least one row, not " + batchSize
                    + ". The batch is what bounds the memory the walk uses.");
        }
        return after(storage -> storage.scan(model, batchSize, block));
    }

    @Override
    public @NotNull CompletableFuture<Integer> writeRows(@NotNull EntityModel<?> model,
                                                         @NotNull List<Object[]> rows) {
        List<Object[]> copy = List.copyOf(rows);
        return after(storage -> storage.writeRows(model, copy));
    }

    @Override
    public @NotNull CompletableFuture<Long> resequence(@NotNull EntityModel<?> model) {
        return after(storage -> storage.resequence(model));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull EntityModel<?> model,
                                                      @NotNull Object id) {
        return after(storage -> storage.delete(model, id));
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteWhere(@NotNull EntityModel<?> model,
                                                           @NotNull List<String> whereColumns,
                                                           @NotNull List<Object> whereValues,
                                                           int limit) {
        List<String> columns = List.copyOf(whereColumns);
        List<Object> values = new ArrayList<>(whereValues);
        return after(storage -> storage.deleteWhere(model, columns, values, limit));
    }

    @Override
    public @NotNull CompletableFuture<SchemaReport> prepare(@NotNull EntityModel<?> model) {
        return after(storage -> storage.prepare(model));
    }

    @Override
    public void close() {
        // The connection belongs to the library, not to one repository's view
        // of it: closing it here would take every other plugin's database with
        // it. Refused rather than ignored, because a plugin that means to close
        // its own store and finds nothing happening would quietly assume it did.
        throw new UnsupportedOperationException("A repository does not own the connection:"
                + " one pool serves the whole server and ExyliaLib closes it on disable.");
    }

    /**
     * Runs an operation once the store is usable.
     *
     * <p>{@code thenCompose} rather than {@code thenComposeAsync}: the body is a
     * single delegate call, and the delegate is what moves the work onto the
     * background pool. Composing asynchronously would put a scheduler round trip
     * in front of a scheduler round trip.
     */
    private <R> CompletableFuture<R> after(@NotNull Function<Storage, CompletableFuture<R>> work) {
        return operations.submit(() -> ready.thenCompose(work));
    }

    /** A target-owned gate that keeps already-queued work alive through release. */
    @FunctionalInterface
    public interface OperationGate {

        <T> @NotNull CompletableFuture<T> submit(@NotNull Supplier<CompletableFuture<T>> operation);
    }

    @Override
    public String toString() {
        return "GatedStorage[" + (ready.isDone() ? "ready" : "preparing") + ']';
    }
}
