package net.exylia.lib.ability;

import org.jetbrains.annotations.NotNull;

/**
 * The plugin's rules, asked before anything else on every attempt.
 *
 * <pre>{@code
 * abilities.gate((ctx, ability) -> rules.factor(ctx, ability));
 * }</pre>
 *
 * <p>{@code 0} or less denies the attempt; {@code 1} keeps it as written; any
 * other value scales the numbers the effects work out (damage, healing,
 * absorption, lifesteal, knockback, money, experience). Chance and cooldown are
 * not scaled. Runs on the holder's thread, on every attempt: read memory only.
 *
 * @since 1.385.0
 */
@FunctionalInterface
public interface AbilityGate {

    /**
     * @param context the attempt, already bound to the holder and level
     * @param ability what is being attempted
     * @return the factor; {@code 0} or less denies
     */
    double factor(@NotNull AbilityContext context, @NotNull Ability ability);
}
