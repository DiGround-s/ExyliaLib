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
 * @since 1.30.0
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

    /**
     * Whether this step goes on until something stops it.
     *
     * <p>A looping body or a looping camera never reaches an end of its own, so
     * a sequence containing one lasts as long as whoever played it lets it: the
     * caller holds the {@link SequenceRun} and cancels it. {@link #trailMillis}
     * stays the length of one cycle, which is what a preview needs in order to
     * show a whole one.
     *
     * @return whether it never finishes on its own
     * @since 1.174.0
     */
    default boolean isEndless() {
        return false;
    }

    /**
     * The slowest tempo a run containing this step may be played at.
     *
     * <p>A step that varies its own speed declares the range here rather than
     * rolling it itself, because the roll belongs to the run: a body and the
     * sounds that keep its beat have to agree about how fast this play is. The
     * runtime rolls once from the widest range its steps declare and every step
     * reads it back with {@link SequenceRun#tempo()}.
     *
     * @return the slowest tempo, where {@code 1} is as written
     * @since 1.177.0
     */
    default double tempoFrom() {
        return 1.0;
    }

    /**
     * The quickest tempo a run containing this step may be played at.
     *
     * @return the quickest tempo, equal to {@link #tempoFrom()} when it does
     *         not vary
     * @since 1.177.0
     */
    default double tempoTo() {
        return 1.0;
    }
}
