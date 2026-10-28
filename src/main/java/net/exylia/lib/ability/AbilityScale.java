package net.exylia.lib.ability;

import org.jetbrains.annotations.NotNull;

/**
 * What {@code bonus-damage: { by: <name> }} multiplies its {@code amount} by:
 * seconds sprinted, hits in a combo, the share of health missing.
 *
 * <pre>{@code
 * abilities.scale("sprint", args -> ctx -> sprint.seconds(ctx.holder()));
 * // bonus-damage: { by: sprint, amount: 2 + level, max: 6 + level * 6 }
 * }</pre>
 *
 * <p>Built in: {@code missing-health} (0 at full health, 1 at none) and
 * {@code health-gap} (0 when the victim's max health is the holder's or less,
 * 1 at twice it or more). Register before compiling.
 *
 * @since 1.387.0
 */
@FunctionalInterface
public interface AbilityScale {

    /**
     * The units for this attempt, on the holder's thread; negative counts as 0.
     *
     * @param context the attempt
     * @return how many times {@code amount} applies, before {@code max}
     */
    double units(@NotNull AbilityContext context);

    /** Builds a scale from the {@code bonus-damage} line, reading any key of its own (a window). */
    @FunctionalInterface
    interface Factory {
        /**
         * @param args the effect's line
         * @return the scale
         */
        @NotNull AbilityScale create(@NotNull AbilityArgs args);
    }
}
