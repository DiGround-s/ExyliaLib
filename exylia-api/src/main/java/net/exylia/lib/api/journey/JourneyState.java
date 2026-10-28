package net.exylia.lib.api.journey;

/**
 * Where a player stands with one journey.
 *
 * @since 3.4.0
 */
public enum JourneyState {
    /** Never started, stopped, or reset. */
    NOT_STARTED,
    /** Running now. */
    ACTIVE,
    /** Finished at least once. */
    COMPLETED,
    /** Skipped by the player. */
    SKIPPED
}
