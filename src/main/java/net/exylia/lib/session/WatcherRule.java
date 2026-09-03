package net.exylia.lib.session;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether a player is watching the server rather than playing on it.
 *
 * <p>Asked about the viewer, never about the target. It is not a permission to
 * be seen; it is a statement that this player's screen should not be edited by
 * anybody else's idea of who belongs where.
 *
 * <p>Asked wherever a mode is about to hide somebody, which may be a packet
 * thread. Read shared state and nothing else.
 *
 * @since 1.96.0
 */
@FunctionalInterface
public interface WatcherRule {

    /**
     * Returns whether this player watches past every mode's isolation.
     *
     * @param viewer the player whose screen is about to be edited
     * @return {@code true} when nothing may be hidden from them
     */
    boolean watching(@NotNull Player viewer);
}
