package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceStep;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Plays a compiled sequence.
 *
 * <h2>Where the work runs</h2>
 * On the thread that owns the sequence's location, through {@code Tasks}. That
 * is what makes it correct on Folia without a single branch: the same call is
 * the main thread on Paper and the region thread on Folia.
 *
 * <h2>Instant sequences cost nothing</h2>
 * A sequence with no delays and no animation is played inline. Most
 * sound-and-particle effects are exactly that, and scheduling a task to do
 * something that finishes in the same tick is a scheduler entry for nothing.
 */
final class SequenceRuntime {

    private static final long TICK_MS = 50L;

    private SequenceRuntime() {
    }

    /**
     * Starts a sequence.
     *
     * @param sequence  what to play
     * @param target    where, and who sees it
     * @param scheduler the owning plugin's scheduler
     * @param onFinish  run once when the sequence stops, however it stops
     * @return the run, for cancelling
     */
    static SequenceRun play(@NotNull Sequence sequence, @NotNull SequenceTarget target,
                            @NotNull TaskScheduler scheduler, @NotNull Runnable onFinish) {
        SequenceRun run = net.exylia.lib.util.sequence.SequenceFactory.run(scheduler, onFinish);
        if (sequence.isEmpty()) {
            run.markFinished();
            return run;
        }
        // The whole sequence is moved onto the location's thread once, rather
        // than each step moving itself: the steps run in order, and hopping per
        // step would let two of them arrive out of order.
        TaskHandle start = scheduler.runAtLocation(target.location(),
                () -> advance(sequence.steps(), 0, target, run));
        run.owns(start);
        return run;
    }

    /**
     * Runs steps from {@code index} until one of them holds the sequence.
     *
     * <p>Iterative rather than one task per step: a sequence of thirty
     * particles and no delays is thirty method calls in one tick, not thirty
     * scheduler entries.
     */
    private static void advance(List<SequenceStep> steps, int index, SequenceTarget target,
                                SequenceRun run) {
        for (int i = index; i < steps.size(); i++) {
            if (run.isCancelled()) {
                return;
            }
            SequenceStep step = steps.get(i);
            try {
                step.play(target, run);
            } catch (RuntimeException broken) {
                // One step that throws must not take the rest of the effect, and
                // must never reach the event that triggered it: a kill effect
                // does not get to cancel a death.
                run.problems().accept(step, broken);
            }
            long hold = step.holdMillis();
            if (hold <= 0L) {
                continue;
            }
            int next = i + 1;
            if (next >= steps.size()) {
                break;
            }
            TaskHandle handle = run.scheduler().runAtLocationLater(target.location(),
                    Math.max(1L, hold / TICK_MS),
                    () -> {
                        if (!run.isCancelled()) {
                            advance(steps, next, target, run);
                        }
                    });
            run.owns(handle);
            return;
        }
        run.finishWhenTrailsEnd();
    }
}
