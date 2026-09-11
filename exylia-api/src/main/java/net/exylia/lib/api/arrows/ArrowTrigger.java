package net.exylia.lib.api.arrows;

/**
 * A moment of a shot.
 *
 * <p>An arrow effect is up to five effects sold as one: a flash at the bow, a
 * line drawn along the flight, and the impact it ends in. Each is declared on
 * its own, and an effect draws nothing at a moment it does not declare.
 *
 * @since 1.3.0
 */
public enum ArrowTrigger {

    /** The shot leaving the bow. */
    LAUNCH,

    /** One step of the line drawn along the flight; a real shot repeats it. */
    TRAIL,

    /** Wherever the shot lands, whatever it hit. */
    HIT,

    /** Landing on something alive; a real shot plays {@link #HIT} for an effect without one. */
    HIT_ENTITY,

    /** Landing on a block; a real shot plays {@link #HIT} for an effect without one. */
    HIT_BLOCK
}
