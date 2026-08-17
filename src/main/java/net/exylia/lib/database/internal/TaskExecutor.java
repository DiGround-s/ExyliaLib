package net.exylia.lib.database.internal;

import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * An {@link Executor} that is really the server's own asynchronous scheduler.
 *
 * <p>{@link java.util.concurrent.CompletableFuture} composition needs an
 * {@code Executor} and the
 * library is not allowed to own a thread pool, so this is the adapter between
 * the two. Every {@link Runnable} handed here becomes one
 * {@code Tasks.runAsync}, which lands on the pool the server already runs —
 * {@code CraftAsyncScheduler} on Paper, {@code FoliaAsyncScheduler} on Folia.
 *
 * <h2>Why not an ExecutorService of our own</h2>
 * A second pool would not take work away from the server; it would add a layer
 * and lose the two things the server's pool gives for free: tasks cancelled
 * when the owning plugin is disabled, and a thread count the server operator
 * can already see. The scarce resource in database work is connections, and
 * that is bounded where it belongs — in Hikari, by {@code maximumPoolSize} —
 * not by rationing the tasks that queue for one.
 *
 * <h2>Threads</h2>
 * Safe from any thread, holds nothing mutable, and cheap enough to construct
 * per plugin.
 *
 * @since 1.24.0
 */
public final class TaskExecutor implements Executor {

    private final Plugin plugin;

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
        Tasks.of(plugin).runAsync(command);
    }

    @Override
    public String toString() {
        return "TaskExecutor[" + plugin.getName() + ']';
    }
}
