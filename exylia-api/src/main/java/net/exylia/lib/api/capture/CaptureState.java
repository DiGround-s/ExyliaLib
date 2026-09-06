package net.exylia.lib.api.capture;

/**
 * Where a running event is in its life.
 *
 * @since 1.0.0
 */
public enum CaptureState {

    /** Built but not started, or already finished and about to be forgotten. */
    IDLE,

    /** Running: the clock ticks and the zones are live. */
    RUNNING,

    /** A winner is decided and the rewards are being handed out. */
    ENDING
}
