package net.exylia.lib.api.betcore;

/**
 * What a record leaderboard can be sorted by.
 *
 * <p>Each names a column the database orders on, which is why the set is closed
 * rather than a free string: a board asked to sort by something that is not
 * indexed would be a table scan on every menu opening.
 *
 * @since 1.0.0
 */
public enum StatsType {

    /** Matches won, most first. */
    WINS,

    /** Matches lost, most first. */
    LOSSES,

    /** Matches nobody won, most first. */
    DRAWS,

    /** Matches finished, most first. */
    PLAYED,

    /** Wins as a percentage of matches played, highest first. */
    WIN_RATE,

    /** The longest run of wins ever held, longest first. */
    BEST_STREAK
}
