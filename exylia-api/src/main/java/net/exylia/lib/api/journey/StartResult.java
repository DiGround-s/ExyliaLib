package net.exylia.lib.api.journey;

/**
 * What came of asking for a journey to start.
 *
 * @since 3.4.0
 */
public enum StartResult {
    /** It is running. */
    STARTED,
    /** No journey has that id. */
    UNKNOWN_JOURNEY,
    /** The journey is switched off in its file. */
    DISABLED,
    /** It is already running for this player. */
    ALREADY_ACTIVE,
    /** It can only be done once, and this player has done it. */
    ALREADY_COMPLETED,
    /** It can be repeated, but not yet. */
    ON_COOLDOWN,
    /** The player does not meet its requirements. */
    REQUIREMENTS_NOT_MET,
    /** Another journey of the same group is running for this player. */
    GROUP_BUSY,
    /** A listener cancelled the {@link net.exylia.lib.api.journey.event.JourneyStartEvent}. */
    CANCELLED,
    /** The player's progress has not finished loading. */
    NOT_LOADED
}
