package net.exylia.lib.api.aimtrainer;

/**
 * The lifecycle of a duel; it only ever moves forward.
 *
 * @since 1.5.0
 */
public enum AimMatchState {
    WAITING,
    STARTING,
    ACTIVE,
    ROUND_END,
    ENDING,
    FINISHED
}
