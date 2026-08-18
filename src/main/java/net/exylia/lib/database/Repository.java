package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Everything one record type can do against the database.
 *
 * <pre>{@code
 * Repository<PlayerStats> stats = databases.repository(PlayerStats.class);
 *
 * stats.find(uuid).thenAccept(found -> ...);
 * stats.save(new PlayerStats(uuid, 1200));
 * stats.where("kit", "boxing").orderByDescending("elo").limit(10).find();
 * }</pre>
 *
 * <h2>Everything is a future</h2>
 * There is no synchronous form of anything. A database call takes as long as
 * the database takes, and the one thread that must never wait for it is the one
 * running the game. ExyliaCommons offered both, and the ecosystem is full of
 * blocking calls made from an event handler because the blocking method existed
 * and was one word shorter.
 *
 * <p>Come back to the game with {@code Tasks}, exactly as anywhere else:
 *
 * <pre>{@code
 * stats.find(player.getUniqueId()).thenAccept(found ->
 *         tasks.runAtEntity(player, () -> found.ifPresent(this::showTo)));
 * }</pre>
 *
 * <h2>Writes</h2>
 * {@link #save} returns as soon as the write is queued and completes when it is
 * durable. There is no flush interval to lose data in: Commons buffered writes
 * for thirty seconds by default, so a crash discarded half a minute of every
 * player's progress on the whole server.
 *
 * @param <T> the record stored
 * @since 1.24.0
 */
public final class Repository<T> {

    private final Storage storage;
    private final EntityModel<T> model;
    private final java.util.function.BiConsumer<String, Throwable> unhandled;

    Repository(Storage storage, EntityModel<T> model) {
        this(storage, model, (message, failure) -> { });
    }

    Repository(Storage storage, EntityModel<T> model,
               java.util.function.BiConsumer<String, Throwable> unhandled) {
        this.storage = storage;
        this.model = model;
        this.unhandled = unhandled;
    }

    /**
     * Reports a failure nobody attached a handler to.
     *
     * <p>Every operation is wrapped in this. A caller that handles the failure
     * itself — {@code exceptionally}, {@code handle}, {@code whenComplete} —
     * still gets it and gets it first; this only covers the future that was
     * dropped, which is otherwise a database error that reaches nobody at all.
     *
     * <p>The bug that prompted it: a plugin wrote
     * {@code find(id).thenAccept(...)} with no error branch, the table could
     * not be read, and the menu it fed simply never opened. No stack trace, no
     * console line, nothing to search the logs for. A failure that is invisible
     * costs more than one that is merely loud.
     */
    private <R> CompletableFuture<R> reported(String operation, CompletableFuture<R> future) {
        future.whenComplete((result, failure) -> {
            if (failure != null) {
                unhandled.accept("A " + operation + " on " + model.table() + " ("
                        + model.type().getSimpleName() + ") failed", failure);
            }
        });
        // The original future, not the one whenComplete returns: handing back
        // the derived one would make a caller's own exceptionally() run after
        // this and change what their chain sees.
        return future;
    }

    /** The record type this stores. */
    public @NotNull Class<T> type() {
        return model.type();
    }

    /** The table or collection it is stored in. */
    public @NotNull String table() {
        return model.table();
    }

    // -------------------------------------------------------------- reading

    /**
     * Reads one record by its id.
     *
     * @param id the id, in record form — a {@code UUID} stays a {@code UUID}
     * @return the record, or empty when there is no such row
     */
    public @NotNull CompletableFuture<Optional<T>> find(@NotNull Object id) {
        return reported("find", storage.find(model, id).thenApply(Optional::ofNullable));
    }

    /**
     * Reads every row.
     *
     * <p>Fine for the tables that hold arenas or kits, which is what most
     * configuration-shaped tables are. Not fine for player data: use
     * {@link #where} or {@link #all} with a limit, or this reads the entire
     * table into memory to find one row.
     *
     * @return every record
     */
    public @NotNull CompletableFuture<List<T>> findAll() {
        return all().find();
    }

    /**
     * Starts a query with no filter.
     *
     * @return a query
     */
    public @NotNull Query<T> all() {
        return new Query<>(this);
    }

    /**
     * Starts a query filtered on one column.
     *
     * @param column the column or record component name
     * @param value  what it must equal
     * @return a query
     */
    public @NotNull Query<T> where(@NotNull String column, @NotNull Object value) {
        return new Query<>(this).where(column, value);
    }

    /**
     * Whether a row with this id exists, without reading it.
     *
     * @param id the id
     * @return whether it is there
     */
    public @NotNull CompletableFuture<Boolean> exists(@NotNull Object id) {
        return reported("exists", storage.exists(model, id));
    }

    /**
     * How many rows there are.
     *
     * @return the count
     */
    public @NotNull CompletableFuture<Long> count() {
        return all().count();
    }

    // -------------------------------------------------------------- writing

    /**
     * Stores a record, inserting or updating as needed.
     *
     * <p>Returns when the row is durable. A caller that does not care can
     * ignore the future; one that does — a purchase, a rank change — should
     * wait for it before telling the player it worked.
     *
     * @param record the record
     * @return completes when written
     */
    public @NotNull CompletableFuture<Void> save(@NotNull T record) {
        return reported("save", storage.save(model, record));
    }

    /**
     * Stores many records in one round trip.
     *
     * <p>Meaningfully faster than a loop of {@link #save}: on MySQL a batched
     * insert is around eight times quicker, and every engine saves the network
     * round trip per row.
     *
     * @param records the records
     * @return completes when all of them are written
     */
    public @NotNull CompletableFuture<Void> saveAll(@NotNull Collection<T> records) {
        return reported("saveAll", storage.saveAll(model, records));
    }

    /**
     * Removes a row by its id.
     *
     * @param id the id
     * @return whether there was a row to remove
     */
    public @NotNull CompletableFuture<Boolean> delete(@NotNull Object id) {
        return reported("delete", storage.delete(model, id));
    }

    // ------------------------------------------------------ used by Query

    CompletableFuture<List<T>> runFind(Query<T> query) {
        return reported("query", storage.select(model, query.filterColumns(), query.filterValues(),
                query.order(), query.limitValue(), query.offsetValue()));
    }

    CompletableFuture<Long> runCount(Query<T> query) {
        return reported("count", storage.count(model, query.filterColumns(), query.filterValues()));
    }

    CompletableFuture<Integer> runDelete(Query<T> query) {
        return reported("delete", storage.deleteWhere(model, query.filterColumns(), query.filterValues(),
                query.limitValue()));
    }

    @Override
    public String toString() {
        return "Repository[" + model.type().getSimpleName() + " -> " + model.table() + ']';
    }
}
