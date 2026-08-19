package net.exylia.lib.util.teleport;

/**
 * Why a teleport is happening.
 *
 * <p>Carried on {@link ExyliaTeleportEvent} so a listener can judge one without
 * knowing who asked. A combat plugin blocks a warp but allows the one that
 * follows a death; a staff plugin lets its own teleports through. Neither needs
 * to depend on whichever plugin started it.
 *
 * <h2>Why a countdown is not one of these</h2>
 * A warmup is <em>how</em> a teleport happened, not why: a warp with a
 * countdown is still a warp, and a {@code /tpa} with one is still a
 * {@code /tpa}. Folding it in here would mean a listener that blocks warps
 * stops seeing the warps that have a countdown, which is every one that
 * matters. A listener that genuinely cares whether the player waited asks
 * {@link TeleportHandle#remainingWarmupSeconds()}.
 *
 * @since 1.34.0
 */
public enum TeleportCause {

    /** A plugin moved the player, with no better description than that. */
    PLUGIN,

    /** The player is being returned to where they were. */
    BACK,

    /** One player accepted another's request to be teleported. */
    TPA,

    /** The player is being sent somewhere chosen at random. */
    RANDOM,

    /** The destination is on another server, so the player is leaving this one. */
    CROSS_SERVER
}
