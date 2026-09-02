package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceStep;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * One step played several times over, on a beat.
 *
 * <p>Rhythm is most of what separates an effect that happens from an effect
 * that is choreographed, and writing it out by hand is five copies of a line
 * with four delays threaded between them. Nobody edits a file like that
 * afterwards, so nobody tunes the rhythm, so every effect ends up with one
 * beat. This is that written once.
 *
 * <h2>Each beat can be turned</h2>
 * {@code turn_each:} advances the shape's rotation every time round, which is
 * how a ring becomes something that sweeps rather than something that blinks.
 * The turned copies are built when the file is read &mdash; a shape's points
 * are shared between them, so five beats cost five wrappers and no extra
 * geometry.
 */
final class RepeatStep implements SequenceStep {

    private static final long TICK_MS = 50L;

    private final List<SequenceStep> beats;
    private final long everyMillis;

    private RepeatStep(List<SequenceStep> beats, long everyMillis) {
        this.beats = List.copyOf(beats);
        this.everyMillis = everyMillis;
    }

    /**
     * Wraps a step so it plays {@code times} over.
     *
     * @param step        what to play
     * @param times       how many times; one hands the step straight back
     * @param everyMillis the gap between beats
     * @param turnEach    extra rotation per beat, in radians, for shapes
     * @return the step to compile
     */
    static @NotNull SequenceStep of(@NotNull SequenceStep step, int times, long everyMillis,
                                    double turnEach) {
        if (times <= 1) {
            return step;
        }
        List<SequenceStep> beats = new ArrayList<>(times);
        for (int beat = 0; beat < times; beat++) {
            beats.add(turnEach != 0.0 && step instanceof ShapeStep shape
                    ? shape.withExtraYaw(turnEach * beat)
                    : step);
        }
        return new RepeatStep(beats, Math.max(TICK_MS, everyMillis));
    }

    @Override
    public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
        // The first beat inline, like any other step: a repeat that starts on
        // the next tick would put a hole at the front of every effect using it.
        beats.get(0).play(target, run);
        for (int beat = 1; beat < beats.size(); beat++) {
            SequenceStep later = beats.get(beat);
            TaskHandle handle = run.scheduler().runAtLocationLater(target.location(),
                    Math.max(1L, beat * everyMillis / TICK_MS),
                    () -> {
                        if (!run.isCancelled()) {
                            later.play(target, run);
                        }
                    });
            run.owns(handle);
        }
    }

    /**
     * How long the whole run of beats keeps drawing.
     *
     * <p>The last beat starts at the end of the rhythm and then draws for as
     * long as one beat does, so a preview waits for the whole thing rather than
     * for the first of it.
     */
    @Override
    public long trailMillis() {
        long longest = 0L;
        for (SequenceStep beat : beats) {
            longest = Math.max(longest, beat.trailMillis());
        }
        return (beats.size() - 1) * everyMillis + longest;
    }
}
