package net.exylia.lib.api.practice;

/**
 * Where a match is in its life.
 *
 * <p>A match exists before anybody can be hit in it and for a moment after the
 * last hit lands, so anything acting on live fighters should check for
 * {@link #ACTIVE} rather than for the match merely being present.
 *
 * @since 1.0.0
 */
public enum MatchStatus {

    /** The arena is being prepared and the players moved in. */
    LOADING,
    /** Everybody is in place, the countdown is running. */
    STARTING,
    /** Being fought. */
    ACTIVE,
    /** A round has been won and the next one is being set up. */
    ROUND_ENDING,
    /** A point was scored and the players are being returned to their spawns. */
    POINT_RESETTING,
    /** The result is decided and the match is being wound down. */
    ENDING,
    /** Over. The match is about to stop existing. */
    ENDED
}
