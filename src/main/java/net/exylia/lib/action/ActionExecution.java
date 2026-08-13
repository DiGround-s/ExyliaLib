package net.exylia.lib.action;

import net.exylia.lib.task.TaskHandle;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A running sequence, which can be told to stop.
 *
 * <p>The reason this exists: a sequence with a delayed step outlives the thing
 * that started it. A menu closes, a player logs out, an item is dropped — and
 * without a handle the remaining steps still run, against a screen nobody is
 * looking at or a player who has left.
 *
 * <pre>{@code
 * ActionExecution running = sequence.execute(context);
 * // when the menu closes:
 * running.cancel();
 * }</pre>
 *
 * <p>Cancelling stops the sequence before its next step. A step already
 * running is not interrupted — killing code halfway through is worse than
 * letting it finish — but nothing after it starts, and any pending delay is
 * cancelled outright rather than left to fire into nothing.
 *
 * @since 1.21.0
 */
public final class ActionExecution {

    private final CompletableFuture<ActionResult> result = new CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** The delay currently waiting, so cancelling does not leave a live task. */
    private final AtomicReference<TaskHandle> pendingDelay = new AtomicReference<>();

    ActionExecution() {
    }

    /**
     * The result of the whole sequence.
     *
     * <p>Completes with the first non-success step, or with success when every
     * step ran. A cancelled sequence completes with
     * {@link ActionResult#stop(String)}.
     *
     * @return the future result
     */
    public @NotNull CompletableFuture<ActionResult> result() {
        return result;
    }

    /** Returns whether the sequence has finished, one way or another. */
    public boolean isDone() {
        return result.isDone();
    }

    /** Returns whether the sequence was cancelled. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Stops the sequence.
     *
     * <p>Safe to call from any thread, and safe to call twice: the second call
     * does nothing rather than completing the result again.
     *
     * @return whether this call was the one that cancelled it
     */
    public boolean cancel() {
        return cancel("cancelled");
    }

    /**
     * Stops the sequence, saying why.
     *
     * @param reason what to report on the result
     * @return whether this call was the one that cancelled it
     */
    public boolean cancel(@NotNull String reason) {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        TaskHandle delay = pendingDelay.getAndSet(null);
        if (delay != null) {
            delay.cancel();
        }
        result.complete(ActionResult.stop(reason));
        return true;
    }

    /** Records the delay currently waiting, so cancelling can stop it. */
    void awaiting(TaskHandle handle) {
        TaskHandle previous = pendingDelay.getAndSet(handle);
        if (previous != null) {
            previous.cancel();
        }
        if (cancelled.get()) {
            // Cancelled while the delay was being scheduled.
            handle.cancel();
            pendingDelay.compareAndSet(handle, null);
        }
    }

    /** Clears the delay once it has fired. */
    void arrived() {
        pendingDelay.set(null);
    }

    /** Completes the sequence, unless it was already cancelled. */
    void finish(ActionResult outcome) {
        if (!cancelled.get()) {
            result.complete(outcome);
        }
    }
}
