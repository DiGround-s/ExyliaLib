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
     * Both corners are set and the session is waiting to be told they are the
     * right ones.
     *
     * <p>Only reached when {@link SelectionOptions#requireConfirmation()} is on.
     * Either corner can still be re-clicked; the session leaves this state by
     * being confirmed or cancelled, never on its own.
     *
     * @since 1.56.0
     */
    AWAITING_CONFIRMATION,
    /** Both valid corners were selected and the result completed successfully. */
    COMPLETED,
    /** The session ended without a result. */
    CANCELLED
}
