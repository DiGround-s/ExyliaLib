package net.exylia.lib.database.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link Executor} that is really the server's own asynchronous scheduler.
 *
 * <p>{@link java.util.concurrent.CompletableFuture} composition needs an
 * {@code Executor} and the
 * library is not allowed to own a thread pool, so this is the adapter between
 * the two. Work handed here runs inside {@code Tasks.runAsync}, which lands on
 * the pool the server already runs — {@code CraftAsyncScheduler} on Paper,
 * {@code FoliaAsyncScheduler} on Folia.
 *
 * <h2>Why not an ExecutorService of our own</h2>
 * A second pool would not take work away from the server; it would add a layer
 * and lose the two things the server's pool gives for free: tasks cancelled
 * when the owning plugin is disabled, and a thread count the server operator
 * can already see.
 *
 * <h2>Why the work is still rationed</h2>
 * Both server pools have no thread ceiling and no queue: every task that finds
 * every thread busy gets a new thread. Database work is mostly waiting — for a
 * Hikari connection, for a query, for Redis — so one task per operation meant
 * one thread per operation in flight, and a burst of lookups against a slow
 * database grew the server's thread count until the JVM could not create
 * another one. So at most {@link #MAX_RUNNING} tasks are handed to the server
 * at a time, and each drains the shared queue until it is empty. Waiting work
 * now costs a queue entry, not a thread.
 *
 * <h2>Threads</h2>
 * Safe from any thread, and cheap enough to construct per plugin.
 *
 * @since 1.24.0
 */
public final class TaskExecutor implements Executor {

    /**
     * Server tasks this executor keeps busy at once.
     *
     * <p>Well above any single connection pool, so a query never waits here
     * when a connection is free.
     */
    // ponytail: fixed ceiling; a caller that blocks a slot on another queued
    // operation (a join inside a callback) needs MAX_RUNNING of them at once to
    // stall the queue. Size it from the open pools if that ever happens.
    private static final int MAX_RUNNING = 32;

    /**
     * Operations allowed to wait at once.
     *
     * <p>With the database down every write waits, and a server keeps writing:
     * an unbounded queue was a slow walk to an out-of-memory crash that took the
     * server down with the database. Past this, work is refused and its future
     * fails, which a caller can see and log instead of a heap that just grows.
     */
    static final int MAX_WAITING = 10_000;

    private final Plugin plugin;
    private final Queue<Runnable> waiting = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();

    /** Whether the current overflow was already reported, so an outage is one line. */
    private final AtomicBoolean overflowing = new AtomicBoolean();

    /**
     * An executor backed by one plugin's scheduler.
     *
     * @param plugin whose tasks these become — the library's, so that work
     *               queued by a plugin already on its way down still runs
     */
    public TaskExecutor(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull Runnable command) {
        if (Bukkit.isStopping()) {
            // Paper hands an async task to its pool only on a main-thread tick,
            // and a stopping server ticks no more: a write queued from a
            // disable would never start, and a disable waiting for it would
            // wait out its whole timeout. Run here, on whoever asked.
            command.run();
            return;
        }
        if (queued.incrementAndGet() > MAX_WAITING) {
            queued.decrementAndGet();
            if (overflowing.compareAndSet(false, true)) {
                Debug.of(plugin).warn("The database is not keeping up: " + MAX_WAITING
                        + " operations are already waiting, so new ones are refused until"
                        + " the queue drains. Is the database reachable?");
            }
            throw new RejectedExecutionException("The database queue is full (" + MAX_WAITING
                    + " operations waiting).");
        }
        waiting.add(command);
        if (!claimSlot()) {
            // A running drain will reach it.
            return;
        }
        try {
            Tasks.of(plugin).runAsync(this::drain);
        } catch (RuntimeException rejected) {
            running.decrementAndGet();
            // Still queued means nobody will run it, so the caller must hear
            // about it; gone means another drain already took it.
            if (waiting.remove(command)) {
                queued.decrementAndGet();
                throw rejected;
            }
        }
    }

    private void drain() {
        try {
            Runnable next;
            while ((next = waiting.poll()) != null) {
                if (queued.decrementAndGet() < MAX_WAITING / 2) {
                    // Half empty again: the next overflow is a new outage,
                    // and worth its own line.
                    overflowing.set(false);
                }
                next.run();
            }
        } finally {
            running.decrementAndGet();
            // Work queued after the last poll but before the decrement found
            // every slot taken and started nothing, so it is picked up here.
            if (!waiting.isEmpty() && claimSlot()) {
                try {
                    Tasks.of(plugin).runAsync(this::drain);
                } catch (RuntimeException rejected) {
                    running.decrementAndGet();
                    throw rejected;
                }
            }
        }
    }

    private boolean claimSlot() {
        int current;
        do {
            current = running.get();
            if (current >= MAX_RUNNING) {
                return false;
            }
        } while (!running.compareAndSet(current, current + 1));
        return true;
    }

    @Override
    public String toString() {
        return "TaskExecutor[" + plugin.getName() + ']';
    }
}
