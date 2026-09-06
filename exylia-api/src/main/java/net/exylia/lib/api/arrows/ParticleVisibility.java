package net.exylia.lib.api.arrows;

/**
 * Who gets to see a player's arrow particles.
 *
 * <p>A player's own setting, not a server one: an archer who finds their own
 * trail distracting turns it off for themselves, and one who does not want to
 * broadcast turns it off for everybody else. The label each mode is shown under
 * is written by the server owner, so it is not carried here — a constant is an
 * identity, not a name.
 *
 * @since 1.0.0
 */
public enum ParticleVisibility {

    /** Everyone sees them. */
    ALL,

    /** Nobody does. */
    NONE,

    /** Only the archer. */
    SELF_ONLY,

    /** Everyone but the archer. */
    OTHERS_ONLY
}
