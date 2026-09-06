package net.exylia.lib.api.betcore;

/**
 * Where a match is in its life, and what that means for the money.
 *
 * <p>The order is the order they happen in, and the one transition that matters
 * more than the rest is {@link #STARTING} to {@link #ACTIVE}: everything before
 * it refunds in full, and everything after it can only end in a payout, a draw
 * refund, or a recovery on the next boot.
 *
 * @since 1.0.0
 */
public enum MatchStatus {

    /** One player, waiting for a second. Nothing has been charged. */
    OPEN,

    /** A second player committed. Both stakes are being taken. */
    LOCKING,

    /** Both stakes are held, the countdown is running, and a cancel refunds. */
    STARTING,

    /** A round is being played and the turn clock is running. */
    ACTIVE,

    /** The round is decided and its result is on screen. */
    ROUND_ENDING,

    /** The series is decided: settling and recording. */
    ENDING,

    /** Over. Cleanup runs shortly after and the match leaves every index. */
    ENDED;

    /**
     * Whether a player may still join.
     *
     * @return {@code true} while the second seat is open
     */
    public boolean isJoinable() {
        return this == OPEN;
    }

    /**
     * Whether the board is on screen and the clocks are running.
     *
     * @return {@code true} while a round is being played or shown
     */
    public boolean isPlaying() {
        return this == ACTIVE || this == ROUND_ENDING;
    }

    /**
     * Whether the stakes can still be handed back in full.
     *
     * @return {@code true} while nothing has been paid out
     */
    public boolean isRefundable() {
        return this == OPEN || this == LOCKING || this == STARTING;
    }

    /**
     * Whether this match is done with.
     *
     * @return {@code true} once the series has been settled
     */
    public boolean isOver() {
        return this == ENDING || this == ENDED;
    }
}
