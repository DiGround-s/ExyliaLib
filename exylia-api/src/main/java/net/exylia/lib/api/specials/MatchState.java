package net.exylia.lib.api.specials;

/**
 * Where a duel is in its life.
 *
 * <p>A match moves forward only, and never goes back to an earlier state.
 *
 * @since 1.0.0
 */
public enum MatchState {

    /** Built, but the players are not in the arena yet. */
    CREATED,

    /** Players are being moved in and equipped. */
    PREPARING,

    /** The duel is running. */
    FIGHTING,

    /** Decided, and the loot window is open. */
    ENDING,

    /** Over. The arena is being put back. */
    FINISHED
}
