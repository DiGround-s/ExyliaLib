package net.exylia.lib.database;

import net.exylia.lib.database.internal.LockRow;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.task.Tasks;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Locks that hold across every server sharing a database, and that a crash
 * cannot leave held.
 *
 * <pre>{@code
 * RowLocks locks = RowLocks.of(database, "auctions");
 *
 * locks.hold(auction.id(), () -> bids.find(auction.id()).thenCompose(this::placeBid))
 *      .thenAccept(done -> {
 *          if (done.isEmpty()) tell(player, "Somebody else is bidding, try again.");
 *      });
 * }</pre>
 *
 * <h2>Why a lease and not a row deleted as a claim</h2>
 * The shape every plugin wrote first: the lock row exists while the thing is
 * free, and whoever deletes it holds it. It has two holes, and both ship. A
 * server that dies holding the lock leaves no row, so the thing is frozen for
 * the whole network until somebody recreates it; and whoever recreates missing
 * rows at startup cannot tell a dead holder from a live one on another server,
 * so the restart hands out a second hold.
 *
 * <p>Here a lock is one row that is never deleted by a hold: who holds it, until
 * when, and a version. Taking it is a {@link Repository#updateIf compare-and-set}
 * on the version, which exactly one caller on the network wins; a holder that
 * stops answering simply lets its lease run out, and the next caller takes it
 * over through the same compare-and-set. A restart writes nothing: a missing
 * row is created with {@link Repository#increment}, which never overwrites one
 * another server already holds.
 *
 * <h2>The lease is a promise about time</h2>
 * A hold that outlives its lease can be taken over while it is still working,
 * and the lease is compared against each server's own clock. Keep the lease
 * well above the longest the work takes plus the clock drift between servers;
 * the default is thirty seconds for work measured in milliseconds. Work that
 * lasts as long as a player keeps something open does not get a longer lease:
 * it uses {@link #holdRenewing(String, Function)}, which keeps a short one
 * alive and says when it was lost.
 *
 * <h2>Threads</h2>
 * Callable from any thread. The work runs on the database's callback thread,
 * never on a game thread: it may read and write rows, and hops through
 * {@code Tasks} before touching the game. Holds on this server for the same
 * key queue behind each other before they reach the database, so a
 * double-click never spends a retry on itself.
 *
 * @since 1.163.0
 */
public final class RowLocks {

    /** How the retry waits; a seam so tests can wait in real time. */
    @FunctionalInterface
    interface Delay {
        void after(long millis, @NotNull Runnable task);
    }

    private final Repository<LockRow> rows;
    private final String namespace;
    private final String holder;
    private final Map<String, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

    private volatile int attempts = 12;
    private volatile long retryMillis = 150L;
    private volatile long leaseMillis = 30_000L;
    private volatile Delay delay;

    private RowLocks(PluginDatabase database, String namespace) {
        this.rows = database.repository(LockRow.class);
        this.namespace = namespace;
        String server;
        try {
            server = Redis.serverId(database.plugin());
        } catch (RuntimeException unreadable) {
            server = database.plugin().getName();
        }
        this.holder = server;
        this.delay = (millis, task) -> Tasks.of(database.plugin())
                .runAsyncLater(Math.max(1L, Ticks.fromMillis(millis)), task);
    }

    /**
     * The locks of one kind of thing, stored in this plugin's database.
     *
     * <p>Every namespace of every plugin sharing a datasource lives in one
     * table, {@code exylia_row_locks}, keyed {@code namespace:key}.
     *
     * @param database  the plugin's database
     * @param namespace what is locked, such as {@code auctions}; unique per table
     * @return the locks, with 12 attempts 150ms apart and a 30 second lease
     */
    public static @NotNull RowLocks of(@NotNull PluginDatabase database, @NotNull String namespace) {
        return new RowLocks(Objects.requireNonNull(database, "database"),
                Objects.requireNonNull(namespace, "namespace"));
    }

    /**
     * How long to keep trying when another server holds the lock.
     *
     * @param attempts tries at most, at least one
     * @param retry    the wait between two tries
     * @return these locks
     */
    public @NotNull RowLocks attempts(int attempts, @NotNull Duration retry) {
        this.attempts = Math.max(1, attempts);
        this.retryMillis = Math.max(1L, retry.toMillis());
        return this;
    }

    /**
     * How long a hold lasts before another server may take it over.
     *
     * @param lease comfortably longer than the work plus the clock drift between servers
     * @return these locks
     */
    public @NotNull RowLocks lease(@NotNull Duration lease) {
        this.leaseMillis = Math.max(1L, lease.toMillis());
        return this;
    }

    /**
     * Runs the work while holding a key's lock, and gives the lock back
     * however the work ends.
     *
     * <p>The lock is released after the work's future completes, successfully
     * or not, and after a work that throws. A value the work completes with is
     * handed back; a failure is handed back as the failure, once the lock is
     * free again.
     *
     * @param key  what to lock, unique within the namespace
     * @param work what to do while holding it; runs on the database callback thread
     * @param <T>  what the work answers
     * @return completes with what the work answered (empty for {@code null}), or
     *         empty without running it when another server held the lock for
     *         every attempt or the database could not be reached
     */
    public <T> @NotNull CompletableFuture<Optional<T>> hold(@NotNull String key,
                                                            @NotNull Supplier<? extends CompletableFuture<T>> work) {
        return hold(key, false, lease -> work.get());
    }

    /**
     * Runs the work while holding a key's lock, renewing the lease for as long
     * as the work is pending, and gives the lock back however the work ends.
     *
     * <p>For a hold measured in minutes rather than milliseconds — a window
     * left open, a player's turn — without a lease that long: the lease is
     * renewed every third of it, so a crashed server's lock is free again one
     * short lease later instead of one long one.
     *
     * <pre>{@code
     * locks.lease(Duration.ofSeconds(60)).holdRenewing(vault.id(), lease -> {
     *     CompletableFuture<Void> closed = openWindow(player, vault);
     *     lease.lost().thenRun(() -> saveAndClose(player, vault));   // taken over, or unreachable
     *     return closed;
     * });
     * }</pre>
     *
     * <p>Renewals stop once the work's future completes and the lock is given
     * back. When a renewal finds the lease taken over, or the database has not
     * answered one before the lease would run out, {@link Lease#lost()}
     * completes and no further renewal is tried; the work is not interrupted,
     * so it is the work that decides to stop. Everything else is as
     * {@link #hold(String, Supplier)}.
     *
     * @param key  what to lock, unique within the namespace
     * @param work what to do while holding it, given its lease; runs on the database callback thread
     * @param <T>  what the work answers
     * @return completes as {@link #hold(String, Supplier)} does
     * @since 1.167.0
     */
    public <T> @NotNull CompletableFuture<Optional<T>> holdRenewing(
            @NotNull String key, @NotNull Function<? super Lease, ? extends CompletableFuture<T>> work) {
        return hold(key, true, work);
    }

    private <T> CompletableFuture<Optional<T>> hold(String key, boolean renewing,
                                                    Function<? super Lease, ? extends CompletableFuture<T>> work) {
        String id = namespace + ':' + key;
        return inTurn(id, () -> acquire(id, 1).thenCompose(version -> {
            if (version == null) {
                return CompletableFuture.completedFuture(Optional.<T>empty());
            }
            Lease lease = new Lease(this, id, version, System.currentTimeMillis() + leaseMillis);
            if (renewing) {
                lease.schedule();
            }
            CompletableFuture<T> done;
            try {
                done = Objects.requireNonNull(work.apply(lease), "The work returned no future.");
            } catch (RuntimeException failure) {
                done = CompletableFuture.failedFuture(failure);
            }
            return done.handle((value, failure) -> lease.release().thenCompose(ignored -> failure == null
                            ? CompletableFuture.completedFuture(Optional.ofNullable(value))
                            : CompletableFuture.<Optional<T>>failedFuture(unwrap(failure))))
                    .thenCompose(Function.identity());
        }));
    }

    /**
     * Removes a key's lock row.
     *
     * <p>Only once the thing it guarded is gone for good, from inside the last
     * hold on it: a caller that deletes the row while another server holds it
     * lets a third one create it again and hold it too.
     *
     * @param key the key
     * @return completes with whether there was a row
     */
    public @NotNull CompletableFuture<Boolean> forget(@NotNull String key) {
        return rows.delete(namespace + ':' + key);
    }

    /**
     * Takes the lock, answering the version it now holds, or {@code null}.
     *
     * <p>A missing row is created with a zero increment, which inserts it or
     * leaves the existing one alone. A read served stale by a cache only loses
     * the compare-and-set, which drops the stale copy, so the retry reads the
     * database.
     */
    private CompletableFuture<Long> acquire(String id, int attempt) {
        return rows.find(id)
                .thenCompose(found -> found.isPresent()
                        ? CompletableFuture.completedFuture(found)
                        : rows.increment(new LockRow(id, "", 0L, 0L), "version").thenCompose(ignored -> rows.find(id)))
                .thenCompose(found -> {
                    LockRow row = found.orElse(null);
                    long now = System.currentTimeMillis();
                    boolean free = row != null
                            && (row.holder() == null || row.holder().isEmpty() || row.expiresAt() <= now);
                    if (!free) {
                        return CompletableFuture.completedFuture((Long) null);
                    }
                    LockRow mine = new LockRow(id, holder, now + leaseMillis, row.version() + 1);
                    return rows.updateIf(mine, "version", row.version())
                            .thenApply(won -> won ? mine.version() : null);
                })
                // Already reported by the repository; a lock that cannot be reached is not held.
                .exceptionally(failure -> null)
                .thenCompose(version -> {
                    if (version != null || attempt >= attempts) {
                        return CompletableFuture.completedFuture(version);
                    }
                    CompletableFuture<Long> next = new CompletableFuture<>();
                    try {
                        delay.after(retryMillis, () -> acquire(id, attempt + 1)
                                .whenComplete((held, failure) -> next.complete(failure == null ? held : null)));
                    } catch (RuntimeException stopped) {
                        next.complete(null);
                    }
                    return next;
                });
    }

    /**
     * One hold's claim on a lock, handed to the work of
     * {@link #holdRenewing(String, Function)}.
     *
     * <p>Every renewal is a compare-and-set on the version the last renewal
     * wrote, so it can only extend a lease this hold still has: once another
     * server took it over, the renewal fails and the lease is lost for good.
     * Renewals, and the release at the end, never overlap.
     *
     * @since 1.167.0
     */
    public static final class Lease {

        private final RowLocks locks;
        private final String id;
        private final CompletableFuture<Void> lost = new CompletableFuture<>();

        private long version;
        private long expiresAt;
        private boolean released;
        private CompletableFuture<Boolean> tail = CompletableFuture.completedFuture(true);

        private Lease(RowLocks locks, String id, long version, long expiresAt) {
            this.locks = locks;
            this.id = id;
            this.version = version;
            this.expiresAt = expiresAt;
        }

        /**
         * Extends the lease by a whole lease from now, after any renewal
         * already on its way.
         *
         * <p>Done on its own every third of the lease; call it only to extend
         * the lease right now, such as before a step known to be slow.
         *
         * @return completes with whether the lease is still this hold's; never
         *         fails. {@code false} after the lock was given back, once the
         *         lease is lost, and for a renewal the database did not take
         *         (which loses the lease only when it would run out first)
         */
        public @NotNull CompletableFuture<Boolean> renew() {
            CompletableFuture<Boolean> next = new CompletableFuture<>();
            CompletableFuture<Boolean> previous;
            synchronized (this) {
                previous = tail;
                tail = next;
            }
            previous.whenComplete((ignored, failure) ->
                    extend().whenComplete((won, error) -> next.complete(Boolean.TRUE.equals(won))));
            return next;
        }

        /**
         * Completes once the lease is lost: a renewal found it taken over, or
         * the database did not answer before it would run out.
         *
         * <p>Another server may hold the lock from then on. Never completes for
         * a hold that ended with its lease intact. Callbacks run on the thread
         * that noticed, which is a database or scheduler thread.
         *
         * @return the loss, completed at most once
         */
        public @NotNull CompletableFuture<Void> lost() {
            return lost;
        }

        private CompletableFuture<Boolean> extend() {
            long current;
            synchronized (this) {
                if (released || lost.isDone()) {
                    return CompletableFuture.completedFuture(false);
                }
                current = version;
            }
            long until = System.currentTimeMillis() + locks.leaseMillis;
            return locks.rows.updateIf(new LockRow(id, locks.holder, until, current + 1), "version", current)
                    .handle((won, failure) -> {
                        if (failure == null && Boolean.TRUE.equals(won)) {
                            synchronized (this) {
                                version = current + 1;
                                expiresAt = until;
                            }
                            return true;
                        }
                        // A refused compare-and-set means somebody else changed the row. A write
                        // that failed only costs this lease when the next try would be too late.
                        if (failure == null || runsOutBeforeNextRenewal()) {
                            lost.complete(null);
                        }
                        return false;
                    });
        }

        /** Renews every third of the lease until the lock is given back or lost. */
        private void schedule() {
            try {
                locks.delay.after(interval(), () -> {
                    boolean pending;
                    synchronized (this) {
                        if (released || lost.isDone()) return;
                        pending = !tail.isDone();
                    }
                    if (!pending) {
                        renew();
                    } else if (runsOutBeforeNextRenewal()) {
                        // A renewal that never answers must not keep the lease looking alive.
                        lost.complete(null);
                        return;
                    }
                    schedule();
                });
            } catch (RuntimeException stopped) {
                // The plugin is gone: the lease runs out on its own.
            }
        }

        private boolean runsOutBeforeNextRenewal() {
            long until;
            synchronized (this) {
                until = expiresAt;
            }
            return System.currentTimeMillis() + interval() >= until;
        }

        private long interval() {
            return Math.max(1L, locks.leaseMillis / 3);
        }

        /** Gives the lock back after any renewal on its way, unless the lease was taken over. */
        private CompletableFuture<Void> release() {
            CompletableFuture<Boolean> previous;
            synchronized (this) {
                released = true;
                previous = tail;
            }
            return previous.handle((ignored, failure) -> null).thenCompose(ignored -> {
                long current;
                synchronized (this) {
                    current = version;
                }
                return locks.rows.updateIf(new LockRow(id, "", 0L, current + 1), "version", current)
                        .handle((won, failure) -> null);
            });
        }
    }

    /**
     * Runs the work once every earlier hold of the same key on this server has
     * finished, and forgets the queue when it is the last one.
     */
    private <R> CompletableFuture<R> inTurn(String id, Supplier<CompletableFuture<R>> work) {
        CompletableFuture<Void> turn = new CompletableFuture<>();
        CompletableFuture<Void> previous = chains.put(id, turn);
        CompletableFuture<R> result = (previous == null
                ? CompletableFuture.<Void>completedFuture(null)
                : previous)
                .thenCompose(ignored -> {
                    try {
                        return work.get();
                    } catch (RuntimeException failure) {
                        return CompletableFuture.<R>failedFuture(failure);
                    }
                });
        result.whenComplete((value, failure) -> {
            turn.complete(null);
            chains.remove(id, turn);
        });
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException wrapped && wrapped.getCause() != null
                ? wrapped.getCause() : failure;
    }

    /** Tests wait in real time; the fake server only runs delayed tasks on a tick. */
    void delayForTests(@NotNull Delay delay) {
        this.delay = delay;
    }

    /** How many keys have a queue on this server, which must fall back to zero. */
    int queuedKeys() {
        return chains.size();
    }
}
