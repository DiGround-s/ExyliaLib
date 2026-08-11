package net.exylia.lib.task.internal;

import net.exylia.lib.task.TaskHandle;

import java.util.Set;

/**
 * Handle implementation shared by every platform.
 *
 * <p>The underlying platform task is bound <em>after</em> scheduling, because a
 * task can start running before the scheduler call returns. {@link #bind} and
 * {@link #cancel()} therefore cooperate: cancelling before the bind still stops
 * the task, and binding after a cancel immediately cancels what was just
 * scheduled.
 *
 * <p>The platform task is stored as a {@link Runnable} canceller rather than a
 * typed field, so this class never references Folia types and stays loadable on
 * plain Bukkit.
 */
public final class TrackedHandle implements TaskHandle {

    private final Set<TrackedHandle> registry;
    private final boolean repeating;

    private volatile boolean cancelled;
    private boolean finished;
    private Runnable canceller;

    TrackedHandle(Set<TrackedHandle> registry, boolean repeating) {
        this.registry = registry;
        this.repeating = repeating;
    }

    /**
     * Attaches the platform task that backs this handle.
     *
     * <p>If the handle was already cancelled or has already finished, the task is
     * cancelled right away instead of being stored.
     *
     * @param canceller cancels the underlying platform task
     */
    void bind(Runnable canceller) {
        synchronized (this) {
            if (!cancelled && !finished) {
                this.canceller = canceller;
                return;
            }
        }
        canceller.run();
    }

    /**
     * Marks a one-shot task as finished so it stops being tracked.
     */
    void complete() {
        synchronized (this) {
            if (cancelled || finished) {
                return;
            }
            finished = true;
            canceller = null;
        }
        registry.remove(this);
    }

    @Override
    public void cancel() {
        Runnable pending;
        synchronized (this) {
            if (cancelled || finished) {
                return;
            }
            cancelled = true;
            pending = canceller;
            canceller = null;
        }
        registry.remove(this);
        if (pending != null) {
            pending.run();
        }
    }

    /**
     * Cancels without touching the registry, used while the registry is being
     * drained to avoid mutating it during iteration.
     */
    void cancelSilently() {
        Runnable pending;
        synchronized (this) {
            if (cancelled || finished) {
                return;
            }
            cancelled = true;
            pending = canceller;
            canceller = null;
        }
        if (pending != null) {
            pending.run();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isRepeating() {
        return repeating;
    }
}
