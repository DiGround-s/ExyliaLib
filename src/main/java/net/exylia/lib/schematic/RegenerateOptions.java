package net.exylia.lib.schematic;

import org.jetbrains.annotations.NotNull;

/**
 * What a regeneration does besides putting the blocks back.
 *
 * <pre>{@code
 * RegenerateOptions.defaults()
 *         .clearEntities(false)       // the armour stands are the build
 *         .moveTrappedPlayers(false); // nobody is in there
 * }</pre>
 *
 * <p>Both default to on, because both describe damage a regeneration does if
 * nobody asks for them: a caller that has not thought about it wants the arena
 * that works rather than the one that suffocates whoever stood in it.
 *
 * @param clearEntities     remove non-player entities inside the bounds first
 * @param moveTrappedPlayers move anyone the new blocks buried up to the nearest air
 * @since 1.48.0
 */
public record RegenerateOptions(boolean clearEntities, boolean moveTrappedPlayers) {

    private static final RegenerateOptions DEFAULTS = new RegenerateOptions(true, true);

    /**
     * Both switches on.
     *
     * @return the defaults
     */
    public static @NotNull RegenerateOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Whether loose entities inside the bounds are removed first.
     *
     * @param value whether to clear them
     * @return a copy with that switch set
     */
    public @NotNull RegenerateOptions clearEntities(boolean value) {
        return new RegenerateOptions(value, moveTrappedPlayers);
    }

    /**
     * Whether players the new blocks buried are moved up.
     *
     * @param value whether to move them
     * @return a copy with that switch set
     */
    public @NotNull RegenerateOptions moveTrappedPlayers(boolean value) {
        return new RegenerateOptions(clearEntities, value);
    }
}
