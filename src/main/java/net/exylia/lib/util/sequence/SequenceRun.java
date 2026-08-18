package net.exylia.lib.util.sequence;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A sequence that is currently playing.
 *
 * <pre>{@code
 * SequenceRun run = effects.play(kill.sequence(), SequenceTarget.at(where));
 *
 * // The player closed the preview before it finished.
 * run.cancel();
 * }</pre>
 *
 * <h2>Why this exists</h2>
 * ExyliaCommons had no handle at all: once a sequence started it always ran to
 * the end. A menu preview that the player closed kept drawing, a trail kept
 * playing after its arrow was gone, and disabling a plugin left its effects
 * finishing on a scheduler that no longer belonged to anybody.
 *
 * <p>Cancelling stops the steps that have not run and the frames already
 * scheduled by the ones that have.
 *
 * @since 1.30.0
 */
public final class SequenceRun {

    private final List<TaskHandle> scheduled = new ArrayList<>(2);
    private final TaskScheduler scheduler;
    private final Runnable onFinish;
    private volatile java.util.function.BiConsumer<SequenceStep, RuntimeException> problems =
            (step, failure) -> { };
    private volatile boolean cancelled;
    private volatile boolean finished;

    SequenceRun(@NotNull TaskScheduler scheduler, @NotNull Runnable onFinish) {
        this.scheduler = scheduler;
        this.onFinish = onFinish;
    }

    /**
     * The scheduler this run's work belongs to.
     *
     * <p>The owning plugin's, so that disabling it cancels everything still to
     * be drawn. A step that needs to schedule anything uses this rather than
     * reaching for a scheduler of its own.
     *
     * @return the scheduler
     */
    public @NotNull TaskScheduler scheduler() {
        return scheduler;
    }

    /**
     * Stops this sequence where it is.
     *
     * <p>Safe from any thread and safe to call twice. Particles already drawn
     * stay drawn &mdash; they belong to the client now &mdash; but nothing
     * further is sent.
     */
    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        List<TaskHandle> copy;
        synchronized (scheduled) {
            copy = List.copyOf(scheduled);
            scheduled.clear();
        }
        for (TaskHandle handle : copy) {
            handle.cancel();
        }
        markFinished();
    }

    /** Whether this run was cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Whether this run has stopped, whether it was cancelled or reached the end. */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Registers work this run owns.
     *
     * <p>Called by steps that schedule anything. A handle registered after the
     * run was cancelled is cancelled immediately rather than stored, which
     * closes the race between a cancel and a frame being scheduled.
     *
     * @param handle the scheduled work
     */
    public void owns(@NotNull TaskHandle handle) {
        if (cancelled) {
            handle.cancel();
            return;
        }
        synchronized (scheduled) {
            scheduled.add(handle);
        }
    }

    /** Drops handles that already ran, so a long trail does not accumulate them. */
    void forget(@NotNull TaskHandle handle) {
        synchronized (scheduled) {
            scheduled.remove(handle);
        }
    }

    /**
     * Where a step that threw is reported.
     *
     * <p>Set by the runtime so a broken step is named against the plugin that
     * owns the sequence, rather than disappearing into a scheduler log.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public @NotNull java.util.function.BiConsumer<SequenceStep, RuntimeException> problems() {
        return problems;
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public void problems(@NotNull java.util.function.BiConsumer<SequenceStep, RuntimeException> reporter) {
        this.problems = reporter;
    }

    /**
     * Ends the run once the work already scheduled has finished.
     *
     * <p>The last step being played is not the end: an animated shape keeps
     * drawing after the sequence has moved past it. Waiting for the scheduled
     * frames is what lets a menu preview hand the player back at the right
     * moment instead of mid-animation.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public void finishWhenTrailsEnd() {
        boolean pending;
        synchronized (scheduled) {
            pending = !scheduled.isEmpty();
        }
        if (!pending) {
            markFinished();
        }
        // Otherwise the frames themselves finish the run: each clears its handle
        // when it completes, and the last one to do so ends it.
    }

    /** Marks this run as over, exactly once. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public void markFinished() {
        if (finished) {
            return;
        }
        finished = true;
        onFinish.run();
    }

    @Override
    public String toString() {
        return "SequenceRun[" + (cancelled ? "cancelled" : finished ? "finished" : "playing") + ']';
    }
}
