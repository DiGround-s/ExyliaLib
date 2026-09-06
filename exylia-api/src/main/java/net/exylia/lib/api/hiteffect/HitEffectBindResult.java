package net.exylia.lib.api.hiteffect;

/**
 * What happened when an effect was bound to a weapon, or taken off one.
 *
 * <p>Every check runs before anything is written, so any result other than
 * {@link #BOUND} or {@link #UNBOUND} means both items were left exactly as they
 * were.
 *
 * @since 1.0.0
 */
public enum HitEffectBindResult {

    /** The weapon now carries the effect. */
    BOUND,

    /** The weapon no longer carries an effect. */
    UNBOUND,

    /** The item is not something an effect can be bound to at all. */
    NOT_A_WEAPON,

    /** A weapon, but not one this effect declares itself to fit. */
    WRONG_WEAPON,

    /** No effect goes by that id, which is also what a stale id reads as. */
    UNKNOWN_EFFECT,

    /** The weapon already carries an effect; unbind before binding another. */
    ALREADY_BOUND,

    /** The weapon carries no effect, so there was nothing to take off. */
    NO_EFFECT,

    /**
     * The bind was refused for a reason this contract does not name.
     *
     * <p>Nothing produces this today. It exists so that a reason added inside
     * the plugin arrives here as a result the caller can handle rather than as
     * an exception thrown at whoever happened to ask, which on a live server is
     * a third-party plugin holding no way to recover.
     */
    FAILED;

    /**
     * Whether the weapon was changed.
     *
     * @return {@code true} for {@link #BOUND} and {@link #UNBOUND}
     */
    public boolean isSuccess() {
        return this == BOUND || this == UNBOUND;
    }
}
