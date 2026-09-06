package net.exylia.lib.api.practice;

/**
 * What a leaderboard is sorted by.
 *
 * <p>Each of these is a column the database keeps an index on, so asking for
 * the top of one is a read of the first rows rather than a sort of every
 * player's row.
 *
 * @since 1.0.0
 */
public enum LeaderboardType {

    /** ELO, which is also what the visible rank is derived from. */
    RANK,
    /** Kills. */
    KILLS,
    /** Deaths. */
    DEATHS,
    /** Kills divided by deaths. */
    KDR,
    /** Matches won. */
    WINS,
    /** Matches lost. */
    LOSSES,
    /** Wins as a share of matches played. */
    WIN_RATE,
    /** The longest win streak the player has ever held. */
    BEST_STREAK,
    /** The win streak the player is on now. */
    CURRENT_STREAK,
    /** Total damage dealt. */
    DAMAGE_DEALT,
    /** Total time spent in matches, in milliseconds. */
    TIME_PLAYED
}
