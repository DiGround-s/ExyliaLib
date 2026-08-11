package net.exylia.lib.task;

/**
 * A cancellable reference to a scheduled task.
 *
 * <p>Every scheduling method in {@link TaskScheduler} returns a handle. Keep it
 * if you need to stop the task later; ignore it if you do not.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TaskHandle beacon = tasks.runTimer(0L, 20L, this::pulse);
 * // ... later
 * beacon.cancel();
 * }</pre>
 *
 * <p>All methods are safe to call from any thread, and {@link #cancel()} is safe
 * to call more than once.
 *
 * @since 1.0.0
 */
public interface TaskHandle {

    /**
     * Cancels this task.
     *
     * <p>For a repeating task, no further executions will happen. An execution
     * already in progress is <strong>not</strong> interrupted; it runs to
     * completion. Calling this on an already cancelled or already finished task
     * does nothing.
     */
    void cancel();

    /**
     * Returns whether this task has been cancelled.
     *
     * <p>This does not become {@code true} when a one-shot task simply finishes
     * normally &mdash; only when the task was actually cancelled.
     *
     * @return {@code true} if the task was cancelled
     */
    boolean isCancelled();

    /**
     * Returns whether this task repeats.
     *
     * @return {@code true} for timers, {@code false} for one-shot tasks
     */
    boolean isRepeating();
}
