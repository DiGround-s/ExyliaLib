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

    /** What the gap is divided by after every beat, for a rhythm that winds up. */
    private final double accel;

    /** The most the gap may be divided by, however many beats have passed. */
    private final double maxSpeed;

    private RepeatStep(List<SequenceStep> beats, long everyMillis, double accel,
                       double maxSpeed) {
        this.beats = List.copyOf(beats);
        this.everyMillis = everyMillis;
        this.accel = accel;
        this.maxSpeed = maxSpeed;
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
        return of(step, times, everyMillis, turnEach, 1.0, 1.0);
    }

    /**
     * The same, on a rhythm that quickens.
     *
     * @param step        what to play
     * @param times       how many times; one hands the step straight back
     * @param everyMillis the gap between the first two beats
     * @param turnEach    extra rotation per beat, in radians, for shapes
     * @param accel       what the gap is divided by after each beat, at least 1
     * @param maxSpeed    the most it may be divided by in total, at least 1
     * @return the step to compile
     * @since 1.177.0
     */
    static @NotNull SequenceStep of(@NotNull SequenceStep step, int times, long everyMillis,
                                    double turnEach, double accel, double maxSpeed) {
        if (times <= 1) {
            return step;
        }
        List<SequenceStep> beats = new ArrayList<>(times);
        for (int beat = 0; beat < times; beat++) {
            beats.add(turnEach != 0.0 && step instanceof ShapeStep shape
                    ? shape.withExtraYaw(turnEach * beat)
                    : step);
        }
        return new RepeatStep(beats, Math.max(TICK_MS, everyMillis),
                Math.max(1.0, accel), Math.max(1.0, maxSpeed));
    }

    /**
     * When a beat falls, in milliseconds from the first one.
     *
     * <p>The gaps are worked out here rather than multiplied out, because a
     * rhythm that winds up has a different gap after every beat and the beat
     * after that has to fall at the sum of them. Divided by the run's tempo:
     * the whole play is quicker or slower than written, and a sound keeping a
     * body's beat has to be quick by exactly as much as the body is.
     */
    private long beatAt(int beat, double tempo) {
        double at = 0.0;
        double speed = tempo;
        for (int step = 0; step < beat; step++) {
            at += everyMillis / speed;
            speed = Math.min(maxSpeed * tempo, speed * accel);
        }
        return (long) at;
    }

    @Override
    public void play(@NotNull SequenceTarget target, @NotNull SequenceRun run) {
        // The first beat inline, like any other step: a repeat that starts on
        // the next tick would put a hole at the front of every effect using it.
        beats.get(0).play(target, run);
        double tempo = run.tempo();
        for (int beat = 1; beat < beats.size(); beat++) {
            SequenceStep later = beats.get(beat);
            TaskHandle handle = run.scheduler().runAtLocationLater(target.location(),
                    Math.max(1L, beatAt(beat, tempo) / TICK_MS),
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
        // At the tempo it was written at: a preview asks how long this lasts
        // before any play of it exists to ask the tempo of.
        return beatAt(beats.size() - 1, 1.0) + longest;
    }
}
