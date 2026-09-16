package net.exylia.lib.util.sequence;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A choreography of effects, compiled once from configuration.
 *
 * <pre>{@code
 * // At load, once:
 * Sequence blast = sequences.compile(config.getStringList("effects"));
 *
 * // On every kill, forever:
 * sequences.play(blast, SequenceTarget.at(victim.getLocation()).by(killer));
 * }</pre>
 *
 * <h2>What it is made of</h2>
 * The same lines ExyliaCommons read, so an existing {@code effects.yml} works
 * unchanged:
 *
 * <pre>{@code
 * effects:
 *   - '[CIRCLE] FLAME;radius:1.5;points:24'
 *   - '[SOUND] ENTITY_BLAZE_DEATH;1.5;0.8'
 *   - '[DELAY] 0.15'
 *   - '[EXPLOSION]'
 * }</pre>
 *
 * <h2>Compiled, not interpreted</h2>
 * Every name is resolved and every number parsed when this object is built. A
 * sequence played ten thousand times parses its strings once, which is the same
 * reason {@code Item} is a definition rather than an {@code ItemStack} and
 * {@code ActionCall} is compiled at load rather than at every click.
 *
 * <p>Immutable and shared: one {@code Sequence} serves every player who
 * triggers it, on any thread.
 *
 * @since 1.30.0
 */
public final class Sequence {

    private final List<SequenceStep> steps;
    private final long durationMillis;
    private final boolean instant;
    private final boolean endless;
    private final double tempoFrom;
    private final double tempoTo;

    Sequence(@NotNull List<SequenceStep> steps) {
        this.steps = List.copyOf(steps);
        long elapsed = 0L;
        long longest = 0L;
        boolean forever = false;
        double slowest = 1.0;
        double quickest = 1.0;
        for (SequenceStep step : this.steps) {
            forever |= step.isEndless();
            // The widest of them: one line asking to vary is the whole
            // sequence varying, because everything in it shares a beat.
            slowest = Math.min(slowest, step.tempoFrom());
            quickest = Math.max(quickest, step.tempoTo());
            // A step's own drawing runs from where the sequence had got to, so
            // the end of the whole thing is the furthest any step reaches, not
            // the sum of the delays.
            longest = Math.max(longest, elapsed + step.trailMillis());
            elapsed += step.holdMillis();
        }
        this.durationMillis = Math.max(elapsed, longest);
        this.instant = durationMillis == 0L && !forever;
        this.endless = forever;
        this.tempoFrom = slowest;
        this.tempoTo = quickest;
    }

    /**
     * An empty sequence, which plays nothing.
     *
     * <p>What a configuration with no {@code effects} list compiles to. Having
     * one instead of {@code null} means a plugin never guards a play call.
     *
     * @return the empty sequence
     */
    public static @NotNull Sequence empty() {
        return new Sequence(List.of());
    }

    /**
     * The slowest tempo a play of this sequence may be rolled at, where
     * {@code 1} is the speed its lines were written at.
     *
     * @since 1.177.0
     */
    public double tempoFrom() {
        return tempoFrom;
    }

    /**
     * The quickest tempo a play may be rolled at; equal to
     * {@link #tempoFrom()} when nothing in it varies.
     *
     * @since 1.177.0
     */
    public double tempoTo() {
        return tempoTo;
    }

    /** The compiled steps, in order. */
    public @NotNull List<SequenceStep> steps() {
        return steps;
    }

    /** Whether there is nothing to play. */
    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * Whether this finishes within the tick it starts.
     *
     * <p>An instant sequence needs no scheduling at all: the runtime plays it
     * inline. Most sound-and-particle effects are instant, and this is what
     * keeps them from costing a task each.
     *
     * @return whether it has no delays and no animation
     */
    public boolean isInstant() {
        return instant;
    }

    /**
     * How long this takes from start to last particle, in milliseconds.
     *
     * <p>Known without playing it, so a menu preview can hand the player back
     * at the right moment. ExyliaCommons summed only the explicit delays and so
     * released the player while animated shapes were still drawing.
     *
     * @return the duration
     */
    public long durationMillis() {
        return durationMillis;
    }

    /**
     * Whether this plays until somebody stops it.
     *
     * <p>True of a sequence with a looping body or a looping camera in it.
     * {@link #durationMillis()} is then one cycle rather than the whole thing,
     * and whoever played it decides when it ends by cancelling its
     * {@link SequenceRun}.
     *
     * @return whether it never finishes on its own
     * @since 1.174.0
     */
    public boolean isEndless() {
        return endless;
    }

    @Override
    public String toString() {
        return "Sequence[" + steps.size() + " steps, " + durationMillis + "ms]";
    }
}
