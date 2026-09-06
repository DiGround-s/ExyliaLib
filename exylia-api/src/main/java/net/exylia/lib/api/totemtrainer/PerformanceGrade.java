package net.exylia.lib.api.totemtrainer;

/**
 * How fast a re-equip was, best first.
 *
 * <p>The millisecond thresholds behind these live in the server's
 * configuration, so the same reaction can grade differently on two servers.
 * Read the grade rather than re-deriving it from
 * {@link net.exylia.lib.api.totemtrainer.event.TotemPopEvent#reactionMillis()}.
 *
 * @since 1.0.0
 */
public enum PerformanceGrade {

    /** The fastest band the server defines. */
    PERFECT,

    /** Faster than most, short of perfect. */
    EXCELLENT,

    /** A solid re-equip. */
    GOOD,

    /** Slow enough to notice, fast enough to survive. */
    OK,

    /** The slowest band. */
    SLOW
}
