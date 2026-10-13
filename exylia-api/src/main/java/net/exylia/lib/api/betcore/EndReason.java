package net.exylia.lib.api.betcore;

/**
 * Why a match ended.
 *
 * @since exylia-api 2.0.0
 */
public enum EndReason {

    /** The rules gave somebody the match; the pot was paid to them. */
    WIN,
    /** Nobody could win; every stake was refunded. */
    DRAW,
    /** A player left mid-match; the pot was paid to whoever stayed. */
    FORFEIT,
    /** Called off before anybody won (an admin, a reload, no free arena); every stake was refunded. */
    CANCELLED;

    /** Whether every stake went back to whoever paid it. */
    public boolean isRefund() {
        return this == DRAW || this == CANCELLED;
    }
}
