package net.exylia.lib.escrow.internal;

/**
 * Where one stake stands.
 *
 * <p>Public only because the database module compiles records by reflection.
 *
 * @since 1.345.0
 */
public enum StakeState {

    /** Written before the money moves; never refunded, because the charge may not have happened. */
    PENDING,
    /** Taken from the player and owed to somebody. */
    HELD,
    /** Paid to the winner. */
    SETTLED,
    /** Given back to whoever staked it. */
    REFUNDED,
    /** The charge never went through, or a restart cut it short mid-charge. */
    VOID
}
