package net.exylia.lib.util.teleport;

/**
 * How a teleport ended.
 *
 * <p>Every request completes with exactly one of these, including the ones that
 * never move anybody. A caller that only wants to know whether it worked reads
 * {@link #isSuccess()}; one that wants to tell the player why not reads the
 * value, because "you moved" and "that spot is inside a wall" are different
 * messages.
 *
 * @since 1.34.0
 */
public enum TeleportResult {

    /** The player arrived. */
    SUCCESS,

    /** Another plugin cancelled {@link ExyliaTeleportEvent}. */
    CANCELLED_BY_EVENT,

    /** The player moved while the countdown was running. */
    CANCELLED_ON_MOVE,

    /** The player took damage while the countdown was running. */
    CANCELLED_ON_DAMAGE,

    /** Somebody called {@link TeleportHandle#cancel()}. */
    CANCELLED_MANUALLY,

    /** The player left before it could finish. */
    PLAYER_LEFT,

    /** The key was still on cooldown, so nothing was attempted. */
    ON_COOLDOWN,

    /** A safe spot was asked for and the search found none. */
    NO_SAFE_LOCATION,

    /** The destination's world is not loaded on this server. */
    WORLD_NOT_FOUND,

    /** The destination is on another server and there is no way to send them. */
    CROSS_SERVER_UNAVAILABLE,

    /**
     * The player asked to go back and there is nowhere recorded.
     *
     * <p>Neither a success nor a cancellation: nobody called it off and nothing
     * was impossible, there was simply no answer to the question. It is the
     * result of a first {@code /back} of a session, so it is the one message
     * that has to read as normal rather than as a fault.
     */
    NOTHING_TO_GO_BACK_TO,

    /** Something else went wrong; the console says what. */
    FAILED;

    /** Whether the player actually arrived. */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Whether something stopped a teleport that was otherwise going to happen.
     *
     * <p>Deliberately narrower than "not success": a destination whose world is
     * missing was never going to work, and telling the player it was cancelled
     * would send them looking for whoever cancelled it.
     *
     * @return whether it was called off rather than impossible
     */
    public boolean isCancelled() {
        return this == CANCELLED_BY_EVENT
                || this == CANCELLED_ON_MOVE
                || this == CANCELLED_ON_DAMAGE
                || this == CANCELLED_MANUALLY;
    }
}
