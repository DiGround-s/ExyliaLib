package net.exylia.lib.api.ffa;

/**
 * One counter a leaderboard can be sorted by.
 *
 * <p>Each constant names a component of {@link FfaStats}, and the plugin has a
 * database index for every one of them — which is why a leaderboard is a
 * choice from this set rather than an arbitrary field name: a counter that is
 * not indexed would make the database sort every row an arena ever wrote.
 *
 * @since 1.0.0
 */
public enum FfaStatistic {

    /** Kills. */
    KILLS,

    /** Deaths. */
    DEATHS,

    /** Kills per death. */
    KDR,

    /** The highest kill streak ever reached. */
    BEST_STREAK,

    /** Kills somebody else finished. */
    ASSISTS,

    /** Total damage dealt. */
    DAMAGE_DEALT,

    /** Seconds spent in the arena, alive or spectating. */
    PLAY_TIME,

    /** Seconds spent alive. */
    ALIVE_TIME,

    /** Kills per minute alive. */
    KILLS_PER_MINUTE
}
