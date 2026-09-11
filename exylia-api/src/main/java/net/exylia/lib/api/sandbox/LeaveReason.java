package net.exylia.lib.api.sandbox;

/**
 * How a player came to be out of a sandbox world.
 *
 * @since 1.3.0
 */
public enum LeaveReason {

    /**
     * Asked to: a command, a menu, another plugin that needed the player and
     * evicted them, or {@link SandBoxService#leave(org.bukkit.entity.Player)}.
     */
    LEFT,

    /** Died in the world, which sends a player back to the lobby rather than to a respawn. */
    DIED,

    /** Disconnected while in the world. */
    DISCONNECTED
}
