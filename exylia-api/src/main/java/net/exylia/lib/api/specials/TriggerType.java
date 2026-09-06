package net.exylia.lib.api.specials;

/**
 * What has to happen before a special item runs its actions.
 *
 * <p>Set per item in its own file, so two copies of the same id always trigger
 * the same way. A name this version does not know falls back to
 * {@link #IMMEDIATE} when the file is read, which is why nothing here is
 * nullable.
 *
 * @since 1.0.0
 */
public enum TriggerType {

    /** Right-clicking the item. */
    IMMEDIATE,

    /** Finishing eating or drinking it. */
    AFTER_CONSUME,

    /** Hitting another player with it. */
    ON_HIT_PLAYER,

    /** A projectile thrown with it landing. */
    ON_PROJECTILE_HIT,

    /** A projectile being thrown with it. */
    ON_PROJECTILE_LAUNCH,

    /** Firing a bow. */
    ON_BOW_SHOOT,

    /** Using it on whoever hit the holder last. */
    ON_LAST_ATTACKER,

    /** Using it on everyone within its configured radius. */
    RADIUS,

    /** Holding it, for as long as it stays in hand. */
    HOLD
}
