package net.exylia.lib.util.sequence;

import org.jetbrains.annotations.NotNull;

/**
 * One line of a sequence, compiled.
 *
 * <p>Everything a step needs was resolved when the file was read: the particle
 * is a {@code Particle}, not a string; the radius is a {@code double}, not a
 * substring. Playing it is arithmetic and packets, with no parsing in between.
 *
 * <p>Implementations live in {@code internal} and are free to change. A plugin
 * never writes one; it writes a line of configuration.
 *
 * @since 1.28.0
 */
public interface SequenceStep {

    /**
     * Performs this step.
     *
     * <p>Runs on the thread that owns the step's location, which the runtime
     * arranges. An implementation may schedule further work of its own &mdash;
     * an animated shape does &mdash; and must register it with the run so that
     * cancelling the sequence cancels that too.
     *
     * @param target where it happens and who sees it
     * @param run    the run this belongs to, for scheduling and cancellation
     */
    void play(@NotNull SequenceTarget target, @NotNull SequenceRun run);

    /**
     * How long this step occupies the sequence before the next one starts.
     *
     * <p>Zero for everything except a delay. Used to work out how long a whole
     * sequence lasts without running it, which a menu preview needs in order to
     * know when to give the player back.
     *
     * @return the time this step holds the sequence for, in milliseconds
     */
    default long holdMillis() {
        return 0L;
    }

    /**
     * How long this step keeps drawing after the sequence has moved on.
     *
     * <p>An animated shape returns for as long as its frames run. A sequence is
     * not finished until every step's trail has finished, which is what stops a
     * preview from releasing the player mid-animation &mdash; ExyliaCommons
     * summed only its delays and released early on every animated effect.
     *
     * @return the time this step keeps drawing for, in milliseconds
     */
    default long trailMillis() {
        return 0L;
    }
}
