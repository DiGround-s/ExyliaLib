package net.exylia.lib.api.capture;

/**
 * Why a capture run ended.
 *
 * @since 1.3.0
 */
public enum CaptureEndReason {

    /** Somebody reached what the mode plays to: held the hill, hit the target, got the cart home. */
    FINISHED,

    /**
     * The clock ran out first. The mode still settles the run from the scores
     * so far, so its top scorers can still be rewarded.
     */
    TIME_UP,

    /**
     * Stopped early, by an admin, an automation or the plugin shutting down.
     * Settled the same way as a run whose clock ran out; see
     * {@link CaptureService#stop(String)}.
     */
    STOPPED
}
