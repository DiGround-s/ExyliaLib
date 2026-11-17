package net.exylia.lib.party;

/**
 * How a ready check ended.
 *
 * @since 1.621.0
 */
public enum ReadyResult {
    /** Every member said they are ready. */
    READY,
    /** A member said they are not. */
    DECLINED,
    /** Not every member answered in time. */
    TIMED_OUT,
    /** A member was off the server when it started. */
    OFFLINE,
    /** The party changed while it ran (someone joined, left or quit), or a newer check replaced it. */
    CHANGED
}
