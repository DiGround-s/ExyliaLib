package net.exylia.lib.api.survival;

/**
 * Why a kit was, or would not be, handed over.
 *
 * <p>The same set answers both questions, because "can this player claim it"
 * and "what happened when they did" have the same reasons. A claim is refused
 * before anything is given, so a player is never left with half a kit and a
 * used-up cooldown.
 *
 * @since 1.0.0
 */
public enum KitClaimStatus {

    /** The kit is ready, or was handed over. */
    SUCCESS,

    /** The player does not hold the permission the kit names. */
    NO_PERMISSION,

    /** They have had it too recently. */
    ON_COOLDOWN,

    /** They have had it as often as the kit allows. */
    MAX_USES_REACHED,

    /** The kit is switched off. */
    KIT_DISABLED,

    /** There is no kit by that id, or the kits module is not running. */
    KIT_NOT_FOUND,

    /** It would not fit, and the kit keeps what does not fit rather than dropping it. */
    NO_ROOM
}
