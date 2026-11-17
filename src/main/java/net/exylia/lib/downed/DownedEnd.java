package net.exylia.lib.downed;

/**
 * How a downed player stopped being downed.
 *
 * @since 1.621.0
 */
public enum DownedEnd {
    /** A teammate brought them back up. */
    REVIVED,
    /** Nobody came in time. */
    BLED_OUT,
    /** They left the server while down. */
    QUIT,
    /** The plugin lifted it ({@link Downed#lift}, {@link Downed#stop}), or they died another way. */
    CANCELLED
}
