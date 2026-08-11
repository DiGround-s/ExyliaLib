package net.exylia.lib.task.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Shared behaviour for every platform implementation: task tracking, tick
 * normalisation, exception isolation, and the convenience overloads that are
 * defined purely in terms of the primitive ones.
 *
 * <p>Subclasses only implement the small set of methods that genuinely differ
 * between Bukkit and Folia.
 */
public abstract class AbstractTaskScheduler implements TaskScheduler {

    /** Ticks per second, used to convert tick delays for the async scheduler. */
    protected static final long TICKS_PER_SECOND = 20L;

    /** Milliseconds in one tick. */
    protected static final long MILLIS_PER_TICK = 1000L / TICKS_PER_SECOND;

    /** The plugin that owns every task scheduled here. */
    protected final Plugin plugin;

    /**
     * Live tasks, kept so they can all be cancelled on disable.
     *
     * <p>Backed by a {@link WeakHashMap} so a handle the consumer dropped and
     * that finished without notifying us can still be collected, and wrapped in
     * a synchronized view because tasks are scheduled and completed from many
     * threads at once.
     */
    private final Set<TrackedHandle> tracked =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    protected AbstractTaskScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Helpers for subclasses
    // ------------------------------------------------------------------

    /**
     * Raises a delay or period to the minimum the platform accepts.
     *
     * <p>Folia throws on non-positive delays, and Bukkit silently treats them as
     * "next tick". Normalising here makes both behave the same.
     *
     * @param ticks the requested tick count
     * @return {@code ticks}, or {@code 1} if it was lower
     */
    protected static long normalizeTicks(long ticks) {
        return Math.max(1L, ticks);
    }

    /** Converts ticks to milliseconds for the async scheduler. */
    protected static long ticksToMillis(long ticks) {
        return normalizeTicks(ticks) * MILLIS_PER_TICK;
    }

    /**
     * Creates and registers a handle.
     *
     * @param repeating whether the task backing it repeats
     * @return a fresh tracked handle
     */
    protected final TrackedHandle newHandle(boolean repeating) {
        TrackedHandle handle = new TrackedHandle(tracked, repeating);
        tracked.add(handle);
        return handle;
    }

    /**
     * Attaches the platform task to a handle. See {@link TrackedHandle#bind}.
     *
     * @param handle    the handle returned to the caller
     * @param canceller cancels the platform task
     */
    protected final void bind(TrackedHandle handle, Runnable canceller) {
        handle.bind(canceller);
    }

    /**
     * Wraps a one-shot task so that it stops being tracked once it has run and
     * so that a thrown exception is logged instead of escaping into the
     * scheduler.
     *
     * @param handle the handle for this task
     * @param task   the user supplied work
     * @return the runnable to hand to the platform
     */
    protected final Runnable once(TrackedHandle handle, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                report(throwable);
            } finally {
                handle.complete();
            }
        };
    }

    /**
     * Wraps a repeating task so that a thrown exception is logged and cancels the
     * timer, rather than escaping or repeating forever in a broken state.
     *
     * @param handle the handle for this task
     * @param task   the user supplied work
     * @return the runnable to hand to the platform
     */
    protected final Runnable repeating(TrackedHandle handle, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                report(throwable);
                handle.cancel();
            }
        };
    }

    private void report(Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE,
                "Task from " + plugin.getName() + " threw an exception", throwable);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public final void cancelAll() {
        TrackedHandle[] snapshot;
        synchronized (tracked) {
            snapshot = tracked.toArray(new TrackedHandle[0]);
            tracked.clear();
        }
        for (TrackedHandle handle : snapshot) {
            handle.cancelSilently();
        }
    }

    @Override
    public final int activeTasks() {
        return tracked.size();
    }

    // ------------------------------------------------------------------
    // Overloads shared by every platform
    // ------------------------------------------------------------------

    @Override
    public final @NotNull TaskHandle runTimer(long delayTicks, long periodTicks, @NotNull Consumer<TaskHandle> task) {
        return selfCancelling(handle -> runTimer(delayTicks, periodTicks, handle), task);
    }

    @Override
    public final @NotNull TaskHandle runAsyncTimer(long delayTicks, long periodTicks, @NotNull Consumer<TaskHandle> task) {
        return selfCancelling(handle -> runAsyncTimer(delayTicks, periodTicks, handle), task);
    }

    @Override
    public final @NotNull TaskHandle runAtEntityTimer(@NotNull Entity entity, long delayTicks, long periodTicks,
                                                      @NotNull Consumer<TaskHandle> task) {
        return selfCancelling(handle -> runAtEntityTimer(entity, delayTicks, periodTicks, handle), task);
    }

    @Override
    public final @NotNull TaskHandle runAtLocationTimer(@NotNull Location location, long delayTicks, long periodTicks,
                                                        @NotNull Consumer<TaskHandle> task) {
        return selfCancelling(handle -> runAtLocationTimer(location, delayTicks, periodTicks, handle), task);
    }

    @Override
    public final @NotNull TaskHandle runAtEntity(@NotNull Entity entity, @NotNull Runnable task) {
        return runAtEntity(entity, task, null);
    }

    /**
     * Builds a timer whose body can cancel the timer itself.
     *
     * <p>The body needs the handle, but the handle only exists once the timer is
     * scheduled, so the reference is published through a one element array and
     * read at execution time. A latch covers the case where the very first run
     * happens before {@code schedule} returns.
     */
    private TaskHandle selfCancelling(java.util.function.Function<Runnable, TaskHandle> schedule,
                                      Consumer<TaskHandle> task) {
        TaskHandle[] slot = new TaskHandle[1];
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);

        TaskHandle handle = schedule.apply(() -> {
            try {
                ready.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            task.accept(slot[0]);
        });

        slot[0] = handle;
        ready.countDown();
        return handle;
    }
}
