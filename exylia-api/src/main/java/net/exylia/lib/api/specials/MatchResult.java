package net.exylia.lib.api.specials;

/**
 * How a duel finished.
 *
 * <p>The two win constants name a side rather than a player because the match
 * knows the sides and the winner may already be offline;
 * {@link SpecialsMatch#playerOne()} and {@link SpecialsMatch#playerTwo()} say
 * who they were.
 *
 * @since 1.0.0
 */
public enum MatchResult {

    /** The first player won. */
    PLAYER_ONE_WIN,

    /** The second player won. */
    PLAYER_TWO_WIN,

    /** Time ran out with both alive. */
    DRAW,

    /** Somebody left, which counts as losing. */
    DISCONNECT,

    /** The match was ended by force, or something went wrong running it. */
    ERROR
}
