package net.exylia.lib.database.internal;

import net.exylia.lib.database.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The one thing a {@link net.exylia.lib.database.Repository} talks to, whatever
 * is underneath it.
 *
 * <p>This is the seam between the module's public half — which thinks in
 * records, ids and filters — and its two implementations, which think in
 * statements or in documents. A repository is written once against this
 * interface and works identically on H2, MySQL, MariaDB, Postgres and MongoDB;
 * that is the whole reason the interface exists, and it is why nothing here
 * mentions a {@code Connection}, a {@code ResultSet} or a {@code Document}.
 *
 * <h2>Everything is a future, and nothing here blocks</h2>
 * A database call takes as long as the database takes, and the one thread that
 * must never wait for it is the one running the game. Implementations move the
 * work onto a background executor and complete the future from there, so
 * calling any method on this interface is cheap from any thread — including the
 * main one, which is where a join handler and a menu click already are.
 *
 * <p>A failure arrives as a future completed exceptionally, never as a thrown
 * exception, with one exception: an argument that is wrong in code rather than
 * in data — a filter naming a column the record does not have — may still throw
 * {@link IllegalArgumentException} synchronously, because that is a bug to fix
 * at the call site and not a condition to recover from.
 *
 * <h2>Ids are in record form</h2>
 * Every {@code id} parameter is the value as the record declares it: a
 * {@code UUID} stays a {@code UUID}, an enum stays an enum. Encoding it to
 * whatever the store holds is the implementation's job, and it has to be, since
 * an id encoded differently from the column that stores it matches nothing and
 * reports that as "no such row" rather than as an error.
 *
 * <h2>Threads</h2>
 * Safe to call from any thread. Implementations are shared by every plugin on
 * the server, so they must be safe from many at once.
 *
 * @see SqlStorage
 * @since 1.24.0
 */
public interface Storage {

    /**
     * Reads one record by its primary key.
     *
     * @param model the compiled record model
     * @param id    the key, in record form
     * @param <T>   the record type
     * @return the record, or {@code null} when there is no such row
     */
    <T> @NotNull CompletableFuture<@Nullable T> find(@NotNull EntityModel<T> model, @NotNull Object id);

    /**
     * Reads the records matching a filter, ordered and paged.
     *
     * @param model        the compiled record model
     * @param whereColumns column or record-component names compared with
     *                     {@code =}, joined by AND; may be empty
     * @param whereValues  the values, in record form, one per column
     * @param order        how to sort, may be empty
     * @param limit        rows at most, {@code 0} or less for all of them
     * @param offset       rows skipped, which only means anything with a limit
     * @param <T>          the record type
     * @return the matching records, in the order asked for
     */
    <T> @NotNull CompletableFuture<List<T>> select(@NotNull EntityModel<T> model,
                                                   @NotNull List<String> whereColumns,
                                                   @NotNull List<Object> whereValues,
                                                   @NotNull List<Query.Sort> order,
                                                   int limit,
                                                   int offset);

    /**
     * Counts the rows matching a filter, without reading them.
     *
     * <p>Asked of the store rather than answered by reading and counting: a
     * count of four hundred thousand rows should not deserialise four hundred
     * thousand records, and with a serialised inventory column that is
     * megabytes of garbage produced to return one number.
     *
     * @param model        the compiled record model
     * @param whereColumns column names compared with {@code =}, may be empty
     * @param whereValues  the values, in record form, one per column
     * @return how many rows match
     */
    @NotNull CompletableFuture<Long> count(@NotNull EntityModel<?> model,
                                           @NotNull List<String> whereColumns,
                                           @NotNull List<Object> whereValues);

    /**
     * Whether a row with this key exists, without reading it.
     *
     * @param model the compiled record model
     * @param id    the key, in record form
     * @return whether it is there
     */
    @NotNull CompletableFuture<Boolean> exists(@NotNull EntityModel<?> model, @NotNull Object id);

    /**
     * Writes one record, inserting or updating as needed.
     *
     * <p>The future completes when the write is durable, not when it is queued.
     * There is no flush interval to lose data in: ExyliaCommons buffered writes
     * for thirty seconds by default, so a crash discarded half a minute of
     * every player's progress on the whole server.
     *
     * @param model  the compiled record model
     * @param record the record
     * @param <T>    the record type
     * @return completes when written
     */
    <T> @NotNull CompletableFuture<Void> save(@NotNull EntityModel<T> model, @NotNull T record);

    /**
     * Writes a record that already has a row, without ever creating one.
     *
     * <p>Only called for a model whose key is generated and a record whose key
     * the store handed out. A key that matches nothing writes nothing: the row
     * an update would have to create would carry a different key from the one
     * the caller is holding.
     *
     * @param model  the compiled record model
     * @param record the record, carrying the key it was stored under
     * @param <T>    the record type
     * @return completes when written
     * @since 1.43.0
     */
    <T> @NotNull CompletableFuture<Void> update(@NotNull EntityModel<T> model, @NotNull T record);

    /**
     * Inserts a record whose key the store hands out, and answers that key.
     *
     * <p>Only ever called for a model whose key is generated; the value the
     * record carries in that component is a placeholder and is not written.
     *
     * @param model  the compiled record model
     * @param record the record
     * @param <T>    the record type
     * @return completes with the assigned key
     * @since 1.32.0
     */
    <T> @NotNull CompletableFuture<Long> insert(@NotNull EntityModel<T> model, @NotNull T record);

    /**
     * Writes many records in one round trip.
     *
     * <p>An empty collection is a completed future and no round trip at all.
     *
     * @param model   the compiled record model
     * @param records the records
     * @param <T>     the record type
     * @return completes when all of them are written
     */
    <T> @NotNull CompletableFuture<Void> saveAll(@NotNull EntityModel<T> model,
                                                 @NotNull Collection<T> records);

    /**
     * Walks the whole table, in primary-key order, one bounded batch at a time.
     *
     * <p>The read half of the row-level seam. Rows are handed over in
     * <em>storage form</em>: an {@code Object[]} in
     * {@link EntityModel#columns()} order holding exactly what the store gave
     * back, which after {@link EntityModel#values} is only ever a primitive
     * wrapper, a {@code String} or a {@code BigDecimal}. No codec runs on this
     * path, so a column holding an {@code ItemStack} is a Base64 string and
     * never becomes a Bukkit object — which is what makes the pair with
     * {@link #writeRows} exact rather than merely equivalent, and what lets a
     * whole table be walked with no game type involved at all.
     *
     * <h2>Constant memory, whatever the table</h2>
     * One batch is alive at a time, and the block is called with it before the
     * next is read. That is the entire reason this exists rather than a
     * {@link #select} with no limit: four hundred thousand rows of serialised
     * inventories read into one list is a heap this server does not have.
     *
     * <h2>Keyset, never offset</h2>
     * Each batch resumes strictly after the last key of the previous one.
     * {@code LIMIT ? OFFSET ?} would make the store produce and discard
     * everything before each page — O(n²) over the walk — and without a total
     * order the rows may come back in a different order per page, so a row is
     * silently handed over twice and another not at all. ExyliaCommons paged
     * that way, without an {@code ORDER BY}, and its exports lost rows.
     *
     * <h2>Threads</h2>
     * The block runs on the database's own background thread, never on the
     * caller's: the future is what the caller waits on, and by the time it
     * completes every batch has already been handed over. A block that touches
     * anything the game owns must come back through {@code Tasks} like any
     * other database callback.
     *
     * <p>The block may keep the list it is given; nothing here reuses it, and
     * where the walk resumes is decided before the block sees it. Whatever the
     * block throws ends the scan and completes the future exceptionally — no
     * further batch is read, and nothing is left open.
     *
     * @param model     the compiled record model
     * @param batchSize rows per batch at most, which is the memory bound
     * @param block     called once per batch, on the database thread
     * @param <T>       the record type
     * @return completes with the total number of rows handed over
     * @throws IllegalArgumentException synchronously if the batch size is not
     *                                  positive, which is a bug at the call
     *                                  site rather than a condition to recover
     *                                  from
     * @since 1.36.0
     */
    <T> @NotNull CompletableFuture<Long> scan(@NotNull EntityModel<T> model,
                                              int batchSize,
                                              @NotNull Consumer<List<Object[]>> block);

    /**
     * Writes rows in storage form, as one batch, upserting by primary key.
     *
     * <p>The write half of the seam, and the exact inverse of {@link #scan}: it
     * takes the arrays that method produced and writes every column of each of
     * them, the key included — an explicitly supplied generated id lands in the
     * table as it was given rather than being replaced by one the store would
     * have handed out. Nothing is decoded and nothing is re-encoded, so what
     * was read is byte for byte what is written.
     *
     * <p>The counter behind a generated key is <em>not</em> moved by this. Two
     * of the four SQL engines leave it where it was after an explicit key, so a
     * repopulated table hands out a key it already holds on the next insert;
     * {@link #resequence} is what fixes that, and it is separate because a
     * caller writing several batches should move the counter once at the end
     * rather than once per batch.
     *
     * <p>An empty list is a completed future and no round trip at all.
     *
     * @param model the compiled record model
     * @param rows  the rows, each in {@link EntityModel#columns()} order and
     *              already encoded
     * @return completes with how many rows were written
     * @since 1.36.0
     */
    @NotNull CompletableFuture<Integer> writeRows(@NotNull EntityModel<?> model,
                                                  @NotNull List<Object[]> rows);

    /**
     * Moves a generated key's counter past every key the table now holds.
     *
     * <p>Called once after {@link #writeRows} has written rows carrying their
     * own ids. Without it the next {@link #insert} asks for a key the table
     * already has and fails on the primary key — on H2 and Postgres, which do
     * not advance the counter for a row that supplied its key, and not on MySQL
     * or MariaDB, which do. That asymmetry is the whole reason this is a method
     * rather than something a caller is expected to remember: a repopulated
     * table that works on the engine somebody tested against and throws on the
     * other two is the failure this module exists to prevent.
     *
     * <p>A no-op, and a completed future, for a model that brings its own key:
     * there is no counter to move. Also a no-op on an empty table, where the
     * counter is already correct wherever it is.
     *
     * @param model the compiled record model
     * @return completes with the value the store will next hand out, or
     *         {@code 0} when there was nothing to do
     * @since 1.36.0
     */
    @NotNull CompletableFuture<Long> resequence(@NotNull EntityModel<?> model);

    /**
     * Removes the row with a primary key.
     *
     * @param model the compiled record model
     * @param id    the key, in record form
     * @return whether there was a row to remove
     */
    @NotNull CompletableFuture<Boolean> delete(@NotNull EntityModel<?> model, @NotNull Object id);

    /**
     * Removes the rows matching a filter.
     *
     * @param model        the compiled record model
     * @param whereColumns column names compared with {@code =}, joined by AND
     * @param whereValues  the values, in record form, one per column
     * @param limit        rows at most, {@code 0} or less for all matches
     * @return how many rows were removed
     */
    @NotNull CompletableFuture<Integer> deleteWhere(@NotNull EntityModel<?> model,
                                                    @NotNull List<String> whereColumns,
                                                    @NotNull List<Object> whereValues,
                                                    int limit);

    /**
     * Makes sure the table or collection behind a model exists, with its
     * indexes.
     *
     * <p>Idempotent, and it has to be: this runs on every server start, and on
     * every start of every server pointed at the same database. It never drops,
     * narrows or renames anything — a schema step that removes a column because
     * a record stopped declaring it is one that deletes a live server's data the
     * first time somebody deploys an old jar.
     *
     * @param model the compiled record model
     * @return what actually changed, which is usually nothing
     */
    @NotNull CompletableFuture<SchemaReport> prepare(@NotNull EntityModel<?> model);

    /**
     * Closes the connection and everything behind it.
     *
     * <p>Called by the library when it disables, never by a plugin: the
     * connection is shared by the whole server, so a plugin closing it would
     * take every other plugin's database with it.
     */
    void close();
}
