package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether a viewer may read a line the server is about to send them.
 *
 * <p>Asked for every system message on its way to a player — a broadcast, a
 * join or quit line, a plugin's announcement — so it must be cheap and must
 * not touch the world: it runs on a packet thread.
 *
 * @since 1.99.0
 */
@FunctionalInterface
public interface MessageRule {

    /**
     * Returns whether {@code viewer} may read this line.
     *
     * @param viewer  the player it is being sent to
     * @param message the line, with every colour and tag stripped out
     * @return {@code false} to drop it
     */
    boolean canRead(@NotNull Player viewer, @NotNull String message);
}
