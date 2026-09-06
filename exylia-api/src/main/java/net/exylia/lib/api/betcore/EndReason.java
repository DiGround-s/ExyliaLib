package net.exylia.lib.api.betcore;

/**
 * Why a match ended.
 *
 * <p>What separates these is not the message they produce but what happens to
 * the money: {@link #WIN}, {@link #SURRENDER}, {@link #TIMEOUT} and
 * {@link #DISCONNECT} pay a winner; {@link #DRAW} and {@link #CANCELLED} hand
 * both stakes back.
 *
 * @since 1.0.0
 */
public enum EndReason {

    /** Somebody won the series on the board. */
    WIN,

    /** Nobody could win it. Both stakes go back. */
    DRAW,

    /** A player gave up. */
    SURRENDER,

    /** A player ran out of time. */
    TIMEOUT,

    /** A player left and did not come back. */
    DISCONNECT,

    /** It never really started, or an admin stopped it. Both stakes go back. */
    CANCELLED;

    /**
     * Whether this outcome hands both stakes back rather than paying a winner.
     *
     * @return {@code true} when both players get their stake back
     */
    public boolean isRefund() {
        return this == DRAW || this == CANCELLED;
    }

    /**
     * Whether this outcome counts towards a player's record.
     *
     * @return {@code true} when the match is written to the statistics
     */
    public boolean isRecorded() {
        return this != CANCELLED;
    }
}
