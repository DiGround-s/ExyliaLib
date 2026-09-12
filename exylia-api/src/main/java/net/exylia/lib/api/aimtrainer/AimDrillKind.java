package net.exylia.lib.api.aimtrainer;

/**
 * What a drill asks of the player.
 *
 * @since 1.5.0
 */
public enum AimDrillKind {

    /** Targets to hit; a hit one comes back somewhere else. Scored in points per minute. */
    FLICK,

    /** One target that lights up after a random wait. Scored on the average reaction. */
    REACTION,

    /** One moving target to keep under the crosshair. Scored on time on target. */
    TRACK,

    /** A player-shaped target that strafes, closes in and is knocked back by every hit. Scored like FLICK, plus combos. */
    COMBO
}
