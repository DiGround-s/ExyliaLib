package net.exylia.lib.api.sandbox;

/**
 * Whether a sandbox world can be played in yet.
 *
 * @since 1.0.0
 */
public enum WorldStatus {

    /** The world is being generated for the first time. */
    CREATING,

    /** Chunks are being pre-generated ahead of the first player. */
    PREGENERATING,

    /** Finished and open. */
    READY,

    /** Configured but not usable: the folder is gone, or loading it failed. */
    UNAVAILABLE
}
