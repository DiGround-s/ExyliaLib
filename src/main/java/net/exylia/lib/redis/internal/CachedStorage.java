package net.exylia.lib.redis.internal;

import net.exylia.lib.database.Query;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A {@link Storage} that answers key lookups from Redis before asking the
 * database, and tells the rest of the network when a row changes.
 *
 * <p>This is the whole cross-server story, and it is smaller than it sounds.
 * Two rules produce it:
 *
 * <ol>
 *   <li><b>A write publishes only after the new value is stored.</b> Store then
 *       publish, never the other way round: a peer woken by the message
 *       immediately re-reads, and if the message could overtake the value it
 *       would cache the row it was just told to drop.</li>
 *   <li><b>A read that misses locally goes to Redis before the database.</b>
 *       That is what makes a player switching servers arrive with the state the
 *       previous server just wrote, without waiting for any message to
 *       arrive.</li>
 * </ol>
 *
 * <h2>Why the join case does not depend on pub/sub</h2>
 * A proxy can move a player between servers inside a tick. If server B had to
 * wait for A's invalidation to land before reading fresh data, the handoff
 * would be a race and it would lose sometimes — which is the failure that looks
 * like "my kill effect reset when I switched servers". It does not wait: B's
 * lookup misses its own memory (the player was not here a moment ago) and goes
 * straight to Redis, where A's write already is. Pub/sub only saves peers that
 * had the row cached from doing the same thing a moment later.
 *
 * <h2>Only what can be keyed is cached</h2>
 * {@link #find} and {@link #exists} are answered from the cache. {@link #select},
 * {@link #count} and {@link #scan} are not: a filter has no stable key, a
 * whole-table walk has no key at all, a leaderboard changes
 * whenever anyone's score does, and caching a query result means invalidating
 * it on writes that no key can predict. ExyliaCommons cached them and paid for
 * it by dropping the entire table's keyspace on every save, which left the
 * cache empty most of the time and did a network-wide {@code SCAN} to get
 * there.
 *
 * <h2>Redis is never load-bearing</h2>
 * Every cache operation is wrapped: a read that fails falls through to the
 * database, and a write whose cache step fails still completes, because the
 * database write is what the caller was promised. What a failure must not do is
 * leave a stale value where a peer can find it, so a store that fails skips the
 * publish that would send peers back to read it.
 *
 * @since 1.31.0
 */
public final class CachedStorage implements Storage {

    private final Storage delegate;
    private final RowCache cache;

    /**
     * Where every Redis call runs: the database's own background executor.
     *
     * <p>Redis is a network round trip, and the thread that asks for a row is
     * very often the server thread. A read that missed this server's memory
     * used to ask Redis right there, inline, and a profile caught it holding a
     * tick for as long as the round trip took. The writes had the same hole:
     * a callback attached to a database future that had already completed runs
     * on the thread attaching it.
     *
     * <p>Inline only when the executor refuses the work, which means the
     * library is shutting down: a write the database already accepted must
     * still reach the cache rather than fail its caller over a scheduler that
     * is gone.
     */
    private final Executor background;

    /**
     * Wraps a storage with a cache.
     *
     * @param delegate what actually stores rows
     * @param cache    the two-level cache and its invalidation channel
     * @param executor where Redis is talked to, never the caller's thread
     */
    public CachedStorage(@NotNull Storage delegate, @NotNull RowCache cache,
                         @NotNull Executor executor) {
        this.delegate = delegate;
        this.cache = cache;
        this.background = task -> {
            try {
                executor.execute(task);
            } catch (RuntimeException refused) {
                task.run();
            }
        };
    }

    // ------------------------------------------------------------------ read

    @Override
    public <T> @NotNull CompletableFuture<@Nullable T> find(@NotNull EntityModel<T> model,
                                                            @NotNull Object id) {
        // Memory answers on the spot. Redis never does: a miss here moves to
        // the background before asking Redis, and only a second miss goes on
        // to the database.
        T hit = cache.local(model, id);
        if (hit != null) {
            return CompletableFuture.completedFuture(hit);
        }
        return CompletableFuture.supplyAsync(() -> cache.get(model, id), background)
                .thenCompose(shared -> shared != null
                        ? CompletableFuture.completedFuture(shared)
                        : delegate.find(model, id).thenApplyAsync(found -> {
                            if (found != null) {
                                // Only a row that exists. Caching "there is no such row" would
                                // need the same invalidation on insert that a row needs on
                                // update, and a first join writes exactly that row moments
                                // later — so the absence is the one thing guaranteed to be
                                // wrong almost immediately.
                                cache.put(model, id, found);
                            }
                            return found;
                        }, background));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> exists(@NotNull EntityModel<?> model,
                                                      @NotNull Object id) {
        // A cached row is proof of existence and costs nothing to check. A miss
        // is not proof of absence, so it asks the database — and does not cache
        // the answer, because this method never sees the row it would store.
        return cache.has(model, id)
                ? CompletableFuture.completedFuture(Boolean.TRUE)
                : delegate.exists(model, id);
    }

    @Override
    public <T> @NotNull CompletableFuture<List<T>> select(@NotNull EntityModel<T> model,
                                                          @NotNull List<String> whereColumns,
                                                          @NotNull List<Object> whereValues,
                                                          @NotNull List<Query.Sort> order,
                                                          int limit,
                                                          int offset) {
        return delegate.select(model, whereColumns, whereValues, order, limit, offset);
    }

    @Override
    public @NotNull CompletableFuture<Long> count(@NotNull EntityModel<?> model,
                                                  @NotNull List<String> whereColumns,
                                                  @NotNull List<Object> whereValues) {
        return delegate.count(model, whereColumns, whereValues);
    }

    // ----------------------------------------------------------------- write

    @Override
    public <T> @NotNull CompletableFuture<Void> save(@NotNull EntityModel<T> model,
                                                     @NotNull T record) {
        // After the database, not before. The cache must never hold a value the
        // database rejected: a constraint violation would otherwise leave every
        // server in the network reading a row that does not exist.
        return delegate.save(model, record).thenApplyAsync(ignored -> {
            cache.put(model, model.id().decode(model.idOf(record)), record);
            return null;
        }, background);
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> update(@NotNull EntityModel<T> model,
                                                       @NotNull T record) {
        // The same order as save, for the same reason: a peer told to re-read
        // before the row is written would cache exactly the value it was told
        // to drop.
        return delegate.update(model, record).thenApplyAsync(ignored -> {
            cache.put(model, model.id().decode(model.idOf(record)), record);
            return null;
        }, background);
    }

    @Override
    public <T> @NotNull CompletableFuture<Long> insert(@NotNull EntityModel<T> model,
                                                       @NotNull T record) {
        // Cached under the key the database chose, which is only known once the
        // insert completed. Nothing else can hold this row yet — no other server
        // can have read a key that did not exist a moment ago — so there is
        // nothing to invalidate, only something to publish.
        return delegate.insert(model, record).thenApplyAsync(key -> {
            T stored = model.withId(record, key);
            // Keyed exactly as save() keys it, off the stored record rather than
            // off the raw number: an int key and a long one must not produce two
            // different cache keys for the same row.
            cache.put(model, model.id().decode(model.idOf(stored)), stored);
            return key;
        }, background);
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> saveAll(@NotNull EntityModel<T> model,
                                                        @NotNull Collection<T> records) {
        List<T> copy = List.copyOf(records);
        return delegate.saveAll(model, copy).thenApplyAsync(ignored -> {
            for (T record : copy) {
                cache.put(model, model.id().decode(model.idOf(record)), record);
            }
            return null;
        }, background);
    }

    // ------------------------------------------------------------- row level

    @Override
    public <T> @NotNull CompletableFuture<Long> scan(@NotNull EntityModel<T> model,
                                                     int batchSize,
                                                     @NotNull Consumer<List<Object[]>> block) {
        // Straight through, like select and count and for the same reason: a
        // whole-table walk has no key to cache under, and filling the cache
        // with every row of a table on the way past would evict the rows
        // players are actually reading.
        return delegate.scan(model, batchSize, block);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Straight through, and it neither fills nor drops the cache.
     *
     * <p>It cannot fill it: what arrives here is storage form and the cache
     * holds records, so caching would mean decoding every row — running exactly
     * the codecs this path exists to avoid — to store rows nobody asked for.
     *
     * <p>It deliberately does not drop the table either, which is the choice
     * worth writing down. A bulk write is called once per batch, and a
     * table-wide invalidation per batch is a network-wide message per thousand
     * rows, each sending every peer back to the database for everything it held
     * of that table. That is ExyliaCommons' own failure — it dropped the
     * table's keyspace on every save — reproduced by the one path that would
     * hit it hardest. So a caller that replaces rows a live server is reading
     * owes the network exactly one invalidation when it has finished, not one
     * per batch, and there is no seam for that here yet: this class is reached
     * through {@link Storage}, which has no "forget this table" of its own.
     * Until there is, the honest statement is that this path is for filling a
     * table nothing is serving from — which is what an import into a fresh
     * table is — and that replacing a live one needs that seam first.
     */
    @Override
    public @NotNull CompletableFuture<Integer> writeRows(@NotNull EntityModel<?> model,
                                                         @NotNull List<Object[]> rows) {
        return delegate.writeRows(model, rows);
    }

    @Override
    public @NotNull CompletableFuture<Long> resequence(@NotNull EntityModel<?> model) {
        // A counter, not a row: nothing here caches one.
        return delegate.resequence(model);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> delete(@NotNull EntityModel<?> model,
                                                      @NotNull Object id) {
        return delegate.delete(model, id).thenApplyAsync(removed -> {
            // Dropped whether or not a row was there. A delete that reports
            // "nothing to remove" against a cache that still holds the row is
            // the one case where the two disagree and the cache is wrong.
            cache.drop(model, id);
            return removed;
        }, background);
    }

    @Override
    public @NotNull CompletableFuture<Integer> deleteWhere(@NotNull EntityModel<?> model,
                                                           @NotNull List<String> whereColumns,
                                                           @NotNull List<Object> whereValues,
                                                           int limit) {
        return delegate.deleteWhere(model, whereColumns, whereValues, limit).thenApplyAsync(removed -> {
            if (removed > 0) {
                // The keys are unknown — a filter deleted them — so the whole
                // table goes. Rare by design: this is the only path that does
                // it, and a plugin calls it on a wipe, not on a player quit.
                cache.dropTable(model);
            }
            return removed;
        }, background);
    }

    @Override
    public @NotNull CompletableFuture<Long> deleteAll(@NotNull EntityModel<?> model) {
        return delegate.deleteAll(model).thenApplyAsync(removed -> {
            // Dropped whether or not anything was there, unlike the filtered
            // delete above. A wipe of a table this server has cached and
            // another server has already emptied still has to clear what is
            // held here, and that is exactly the case where the count is zero.
            cache.dropTable(model);
            return removed;
        }, background);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public @NotNull CompletableFuture<net.exylia.lib.database.internal.SchemaReport> prepare(
            @NotNull EntityModel<?> model) {
        // Nothing to cache about a CREATE TABLE, and this is also where the
        // table registers for invalidation: a peer's message names a table, and
        // only a table something here reads is worth dropping anything for.
        cache.register(model);
        return delegate.prepare(model);
    }

    @Override
    public void close() {
        // The cache belongs to the target, which closes it: this storage is one
        // of several sharing it. Closing it here would blind every other
        // plugin's repositories on the same datasource.
        delegate.close();
    }

    @Override
    public String toString() {
        return "CachedStorage[" + delegate + ']';
    }
}
