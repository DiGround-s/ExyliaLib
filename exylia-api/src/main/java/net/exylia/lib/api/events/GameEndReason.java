package net.exylia.lib.api.events;

/**
 * Why a run ended.
 *
 * @since 1.3.0
 */
public enum GameEndReason {

    /** Played to a result: the game decided, winners or not, and announced it. */
    FINISHED,

    /**
     * Stopped before a result: by an admin, a shutdown, a waiting event nobody
     * joined in time, or the last player walking out before it began. Nobody
     * wins one.
     */
    STOPPED
}
