package net.exylia.lib.api.practice;

/**
 * How a finished match went for one of the players in it.
 *
 * <p>Recorded per player rather than per match, so the same match appears as a
 * {@link #WIN} in one player's history and a {@link #LOSS} in the other's.
 *
 * @since 1.0.0
 */
public enum MatchResult {

    /** The player won. */
    WIN,
    /** The player lost. */
    LOSS,
    /** Nobody won: a draw, or a match that ended without a decision. */
    DRAW
}
