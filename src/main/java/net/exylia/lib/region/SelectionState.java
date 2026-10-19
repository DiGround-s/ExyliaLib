package net.exylia.lib.region;

/**
 * Lifecycle state of an interactive region selection.
 *
 * @since 1.23.0
 */
public enum SelectionState {
    /** The session is accepting selector interactions. */
    ACTIVE,
    /**
     * The shape is complete and the session is waiting to be told it is the
     * right one.
     *
     * <p>Reached when {@link SelectionOptions#requireConfirmation()} is on, and
     * always for an outline, which has no last click. Every point can still be
     * re-clicked; the session leaves this state by being confirmed or
     * cancelled, never on its own.
     *
     * @since 1.56.0
     */
    AWAITING_CONFIRMATION,
    /** The shape was picked and the result completed successfully. */
    COMPLETED,
    /** The session ended without a result. */
    CANCELLED
}
