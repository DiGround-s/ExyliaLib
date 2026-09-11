package net.exylia.lib.api.classes;

/**
 * What came of asking a player to use an ability.
 *
 * <p>Every refusal except {@link #CANCELLED} has already been told to the
 * player in the plugin's own words, the way a right click would have been.
 *
 * @since 1.3.0
 */
public enum AbilityUseResult {

    /** The ability went off: energy spent, cooldown started, effects applied. */
    USED,

    /** The player is in no class. Warming up into one counts as none. */
    NOT_IN_CLASS,

    /** Their class has no ability on that trigger. */
    UNKNOWN_ABILITY,

    /** The ability is still cooling down. */
    ON_COOLDOWN,

    /** They hold less energy than the ability costs. */
    NOT_ENOUGH_ENERGY,

    /**
     * A handler of {@link net.exylia.lib.api.classes.event.AbilityUseEvent}
     * refused it, and is expected to have told the player why.
     */
    CANCELLED
}
