package net.exylia.lib.ability;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * What one attempt came to, for metrics.
 *
 * <pre>{@code
 * abilities.onOutcome(outcome -> {
 *     if (outcome.result() == AbilityOutcome.Result.PROC) metrics.record(outcome);
 * });
 * }</pre>
 *
 * <p>Holds ids, never a {@code Player}: keeping one past the call keeps a whole
 * world alive. Built only while somebody listens.
 *
 * @param ability the ability's id
 * @param trigger the trigger that fired it
 * @param level   the level it ran at
 * @param holder  who held it
 * @param result  how far it got
 * @param damage  damage reported, {@code 0} unless it procced
 * @param healing healing reported
 * @param targets entities affected
 * @param drops   items dropped or given
 * @since 1.385.0
 */
public record AbilityOutcome(@NotNull String ability, @NotNull String trigger, int level,
                             @NotNull UUID holder, @NotNull Result result,
                             double damage, double healing, int targets, int drops) {

    /** Where an attempt stopped, in the order they are checked. */
    public enum Result {
        /** The gate answered 0. */
        GATED,
        /** A condition did not hold. */
        CONDITIONS,
        /** Still on cooldown. */
        COOLDOWN,
        /** The roll missed. */
        CHANCE,
        /** It went off. */
        PROC
    }
}
