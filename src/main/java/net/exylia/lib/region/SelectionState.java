package net.exylia.lib.region;

/**
 * Lifecycle state of an interactive region selection.
 *
 * @since 1.23.0
 */
public enum SelectionState {
    /** The session is accepting selector interactions. */
    ACTIVE,
    /** Both valid corners were selected and the result completed successfully. */
    COMPLETED,
    /** The session ended without a result. */
    CANCELLED
}
