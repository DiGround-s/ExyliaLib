package net.exylia.lib.database;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * What to select, sort by and how much of it.
 *
 * <p>Built by {@link Repository#where}, {@link Repository#all} and friends; a
 * consumer rarely names this type.
 *
 * <pre>{@code
 * // one player's matches, newest first, at most ten
 * repo.where("winner_uuid", uuid)
 *     .orderByDescending("played_at")
 *     .limit(10)
 *     .find()
 *     .thenAccept(this::show);
 * }</pre>
 *
 * <p>Every filter is an equality on a column, which is what the ecosystem's
 * forty-nine existing lookups are. Anything richer belongs in the plugin, not
 * in a query language nobody asked for: a repository that grows a general
 * expression tree has become an ORM.
 *
 * @param <T> the record queried
 * @since 1.24.0
 */
public final class Query<T> {

    private final Repository<T> repository;
    private final List<String> filterColumns = new ArrayList<>(2);
    private final List<Object> filterValues = new ArrayList<>(2);
    private final List<Sort> order = new ArrayList<>(1);

    private int limit;
    private int offset;

    Query(Repository<T> repository) {
        this.repository = repository;
    }

    /** A column sorted one way. */
    public record Sort(@NotNull String column, boolean ascending) {
    }

    /**
     * Narrows to rows whose column equals a value.
     *
     * <p>Repeating this narrows further; the conditions are combined with AND.
     *
     * @param column the column or record component name
     * @param value  what it must equal, in record form
     * @return this query
     */
    public @NotNull Query<T> where(@NotNull String column, @NotNull Object value) {
        filterColumns.add(column);
        filterValues.add(value);
        return this;
    }

    /**
     * Sorts by a column, smallest first.
     *
     * @param column the column or record component name
     * @return this query
     */
    public @NotNull Query<T> orderBy(@NotNull String column) {
        order.add(new Sort(column, true));
        return this;
    }

    /**
     * Sorts by a column, largest first.
     *
     * <p>What a leaderboard wants.
     *
     * @param column the column or record component name
     * @return this query
     */
    public @NotNull Query<T> orderByDescending(@NotNull String column) {
        order.add(new Sort(column, false));
        return this;
    }

    /**
     * Takes at most this many rows.
     *
     * <p>Worth setting on anything a player can trigger. A leaderboard menu
     * shows ten names whether the table holds ten rows or four hundred
     * thousand, and reading the other three hundred thousand costs the same
     * whether or not anybody looks at them.
     *
     * @param rows the most to return
     * @return this query
     */
    public @NotNull Query<T> limit(int rows) {
        this.limit = Math.max(0, rows);
        return this;
    }

    /**
     * Skips this many rows first, for paging.
     *
     * <p>Only meaningful with a {@link #limit} and an order: without an order
     * the database is free to return rows differently each time, so page two
     * may repeat or skip what page one showed.
     *
     * @param rows how many to skip
     * @return this query
     */
    public @NotNull Query<T> skip(int rows) {
        this.offset = Math.max(0, rows);
        return this;
    }

    /**
     * Runs the query.
     *
     * @return the matching records, in the order asked for
     */
    public @NotNull java.util.concurrent.CompletableFuture<List<T>> find() {
        return repository.runFind(this);
    }

    /**
     * Runs the query and takes the first row.
     *
     * @return the first match, or empty
     */
    public @NotNull java.util.concurrent.CompletableFuture<java.util.Optional<T>> findFirst() {
        return limit(1).find().thenApply(rows ->
                rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.getFirst()));
    }

    /**
     * Counts the matching rows without reading them.
     *
     * <p>The point of asking the database rather than reading and counting: a
     * count of four hundred thousand rows should not deserialise four hundred
     * thousand records, and with a serialised inventory column that is
     * megabytes of garbage to produce one number.
     *
     * @return how many rows match
     */
    public @NotNull java.util.concurrent.CompletableFuture<Long> count() {
        return repository.runCount(this);
    }

    /**
     * Adds up one numeric column over the matching rows, without reading them.
     *
     * <p>The total of a currency across every player is one number the
     * database already knows how to produce; reading four hundred thousand
     * balances to add them here would be the same garbage {@link #count}
     * avoids, and paging through them would count a balance that moved between
     * pages twice. Order, limit and skip do not apply: a sum is over every row
     * the filter lets through.
     *
     * <pre>{@code
     * balances.where("currency", "coins").sum("amount")
     *     .thenAccept(total -> log("coins in circulation: " + total));
     * }</pre>
     *
     * @param column a numeric column, by column or record component name
     * @return the total, {@code 0} when nothing matches
     * @throws IllegalArgumentException if the column is not numeric or not on
     *                                  the record
     * @since 1.183.0
     */
    public @NotNull java.util.concurrent.CompletableFuture<java.math.BigDecimal> sum(@NotNull String column) {
        return repository.runSum(this, column);
    }

    /**
     * Deletes the matching rows in the database.
     *
     * <p>Nothing is read. Used for retention sweeps, where reading a page in
     * order to delete it is both slower and, under a write cache, an infinite
     * loop: the next read returns the very rows whose deletion is still queued.
     *
     * @return how many rows were removed
     */
    public @NotNull java.util.concurrent.CompletableFuture<Integer> delete() {
        return repository.runDelete(this);
    }

    List<String> filterColumns() {
        return filterColumns;
    }

    List<Object> filterValues() {
        return filterValues;
    }

    List<Sort> order() {
        return order;
    }

    int limitValue() {
        return limit;
    }

    int offsetValue() {
        return offset;
    }
}
