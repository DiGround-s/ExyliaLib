package net.exylia.lib.ability;

import org.jetbrains.annotations.NotNull;

/**
 * What one proc did, written by its effects as they run.
 *
 * <p>Read back into an {@link AbilityOutcome}. Not thread safe: it lives for
 * one proc on one thread.
 *
 * @since 1.385.0
 */
public final class AbilityReport {

    private double damage;
    private double healing;
    private int targets;
    private int drops;

    /** Adds damage dealt, over time included. */
    public @NotNull AbilityReport damage(double amount) {
        damage += Math.max(0, amount);
        return this;
    }

    /** Adds health restored. */
    public @NotNull AbilityReport healing(double amount) {
        healing += Math.max(0, amount);
        return this;
    }

    /** Counts one more entity affected. */
    public @NotNull AbilityReport target() {
        targets++;
        return this;
    }

    /** Adds items dropped or given. */
    public @NotNull AbilityReport drops(int amount) {
        drops += Math.max(0, amount);
        return this;
    }

    /** Damage so far. */
    public double damage() {
        return damage;
    }

    /** Healing so far. */
    public double healing() {
        return healing;
    }

    /** Entities affected so far. */
    public int targets() {
        return targets;
    }

    /** Items so far. */
    public int drops() {
        return drops;
    }
}
