package net.exylia.lib.api.totemtrainer;

/**
 * The lifecycle of a match. It only ever moves forward, so a state that is
 * {@linkplain #isOver() over} will never be anything else.
 *
 * @since 1.0.0
 */
public enum MatchState {

    /** Built, but both players have not arrived in the arena yet. */
    WAITING,

    /** Both are in the arena and a round's countdown is running. */
    STARTING,

    /** A round is being played. */
    ACTIVE,

    /** The round is scored and its result is being shown. */
    ROUND_END,

    /** The series is decided and is being settled. */
    ENDING,

    /** Over. Ratings and history are written after this. */
    FINISHED;

    /**
     * Whether the match is finished or on its way there.
     *
     * @return {@code true} once nothing can change the result
     */
    public boolean isOver() {
        return this == ENDING || this == FINISHED;
    }
}
