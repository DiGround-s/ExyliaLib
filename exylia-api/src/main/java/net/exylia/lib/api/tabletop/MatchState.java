package net.exylia.lib.api.tabletop;

/**
 * Where a match is in its life. Each state is entered once, in this order.
 *
 * @since exylia-api 3.0.0
 */
public enum MatchState {

    /** The host's stake is being taken. */
    CREATING,
    /** Waiting: listed in the lobby, or sent to one player as a duel. */
    OPEN,
    /** Full: every stake is held and the players are on their way to an arena. */
    STARTING,
    /** Being played. */
    PLAYING,
    /** Over, paid out or refunded. */
    ENDED
}
