package net.exylia.lib.database.internal;

import net.exylia.lib.database.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
