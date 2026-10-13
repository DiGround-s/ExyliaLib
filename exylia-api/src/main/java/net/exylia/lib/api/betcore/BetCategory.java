package net.exylia.lib.api.betcore;

/**
 * The two kinds of game, each switched on or off as a whole by the server.
 *
 * @since exylia-api 2.0.0
 */
public enum BetCategory {

    /** Player against player, where skill decides. */
    VERSUS,

    /** Player against the house, where luck decides. */
    CHANCE
}
