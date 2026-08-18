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

    Sequence(@NotNull List<SequenceStep> steps) {
        this.steps = List.copyOf(steps);
        long elapsed = 0L;
        long longest = 0L;
        for (SequenceStep step : this.steps) {
            // A step's own drawing runs from where the sequence had got to, so
            // the end of the whole thing is the furthest any step reaches, not
            // the sum of the delays.
            longest = Math.max(longest, elapsed + step.trailMillis());
            elapsed += step.holdMillis();
        }
        this.durationMillis = Math.max(elapsed, longest);
        this.instant = durationMillis == 0L;
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

    @Override
    public String toString() {
        return "Sequence[" + steps.size() + " steps, " + durationMillis + "ms]";
    }
}
