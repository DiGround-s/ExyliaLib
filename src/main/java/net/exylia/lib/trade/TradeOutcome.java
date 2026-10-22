package net.exylia.lib.trade;

/**
 * How a trade ended. Exactly one is delivered, exactly once.
 *
 * @since 1.349.0
 */
public enum TradeOutcome {

    /** Both confirmed and the countdown ran out: the items are the caller's now. */
    CONFIRMED,

    /** A player closed their window. */
    CLOSED,

    /** A player left the server. */
    LEFT,

    /** A player died; their items reach them on respawn. */
    DIED,

    /** Nobody finished before the trade's timeout. */
    TIMED_OUT,

    /** It could not start: a player was offline, dead, already trading, or the window was refused. */
    UNAVAILABLE,

    /** The owning plugin was disabled, or the server is stopping. */
    SHUT_DOWN;

    /**
     * Whether the items changed hands.
     *
     * @return {@code true} only for {@link #CONFIRMED}
     */
    public boolean confirmed() {
        return this == CONFIRMED;
    }
}
