package net.exylia.lib.ability;

import org.jetbrains.annotations.NotNull;

/**
 * Whether an ability may go off, compiled from one line of its
 * {@code conditions:}.
 *
 * <pre>{@code
 * abilities.condition("in_mine", args -> ctx -> mines.contains(ctx.location()));
 * }</pre>
 *
 * <p>Runs on the holder's thread on every attempt that passed the gate: read
 * memory, never a database.
 *
 * @since 1.385.0
 */
@FunctionalInterface
public interface AbilityCondition {

    /**
     * @param context the attempt
     * @return whether it may go on
     */
    boolean test(@NotNull AbilityContext context);

    /**
     * Builds a condition from what the YAML wrote, once, at load.
     *
     * <p>Read every key here, not in {@link #test}: a key nobody read while
     * compiling is reported as unknown.
     */
    @FunctionalInterface
    interface Factory {

        /**
         * @param args the line's arguments
         * @return the compiled condition
         * @throws IllegalArgumentException when the arguments cannot be used; reported, and the line is skipped
         */
        @NotNull AbilityCondition create(@NotNull AbilityArgs args);
    }
}
