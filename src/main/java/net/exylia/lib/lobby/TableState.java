package net.exylia.lib.lobby;

/**
 * Where a {@link Table} is in its life. Each state is entered once, in this order; any state but
 * {@link #ENDED} may jump to {@link #ENDED}.
 *
 * @since 1.356.0
 */
public enum TableState {

    /** The host's seat is being admitted (a stake held, a check run). Not listed yet. */
    CREATING,
    /** Waiting for players: listed as an open table, or sent to one player as an invitation. */
    OPEN,
    /** Full, or started early by its host: the plugin is seating the players. */
    STARTING,
    /** Being played. */
    PLAYING,
    /** Over: finished, cancelled, expired or declined. */
    ENDED
}
