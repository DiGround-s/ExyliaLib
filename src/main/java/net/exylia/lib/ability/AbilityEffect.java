package net.exylia.lib.ability;

import org.jetbrains.annotations.NotNull;

/**
 * What an ability does, compiled from one line of its {@code effects:}.
 *
 * <pre>{@code
 * final class BleedEffect implements AbilityEffect {
 *     private final Formula amount;
 *     BleedEffect(AbilityArgs args) { this.amount = args.formula("amount", "1"); }
 *     public void apply(AbilityContext ctx, AbilityReport report) {
 *         LivingEntity victim = ctx.victim();
 *         if (victim == null) return;
 *         double hit = amount.applyOr(0, ctx.level()) * ctx.scale();
 *         ctx.damage(victim, hit);
 *         report.damage(hit).target();
 *     }
 * }
 * abilities.effect("bleed", BleedEffect::new);
 * }</pre>
 *
 * <h2>Contracts</h2>
 * {@link #apply} runs on the holder's thread (their region on Folia). An entity
 * owned by another region is reached through {@link AbilityContext#at}. Nothing
 * blocks and nothing touches a database. Damage dealt through
 * {@link AbilityContext#damage} never fires ATTACK or HIT_TAKEN again unless the
 * ability says {@code chain: true}.
 *
 * @since 1.385.0
 */
@FunctionalInterface
public interface AbilityEffect {

    /**
     * Does the thing and says what it did.
     *
     * @param context the proc
     * @param report  where to count damage, healing, targets and drops
     */
    void apply(@NotNull AbilityContext context, @NotNull AbilityReport report);

    /**
     * Undoes {@link #apply} for a PASSIVE ability that stopped being held.
     *
     * <p>Nothing by default. The built-in {@code potion} removes its effect.
     *
     * @param context the unequip, bound to the same holder and level
     */
    default void revert(@NotNull AbilityContext context) {
    }

    /**
     * Builds an effect from what the YAML wrote, once, at load.
     *
     * <p>Read every key here, not in {@link #apply}: a key nobody read while
     * compiling is reported as unknown.
     */
    @FunctionalInterface
    interface Factory {

        /**
         * @param args the line's arguments
         * @return the compiled effect
         * @throws IllegalArgumentException when the arguments cannot be used; reported, and the line is skipped
         */
        @NotNull AbilityEffect create(@NotNull AbilityArgs args);
    }
}
