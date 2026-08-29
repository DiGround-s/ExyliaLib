package net.exylia.lib.packet;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Pinning a player where they stand.
 *
 * <p>Every position packet the client sends while frozen is dropped and
 * answered with a teleport back to the anchor, so the server never sees the
 * move and the client snaps back at once. Looking around is still allowed.
 * A {@code PlayerMoveEvent} guard backs the packet path up on servers where
 * PacketEvents is missing, which is the one case where the client may drift
 * a tick before being put back.
 *
 * <h2>Limits</h2>
 * Knockback, pistons and a plugin teleport still move the player server-side;
 * the anchor does not follow. Unfreeze and freeze again after moving them on
 * purpose.
 *
 * @since 1.75.0
 */
public interface Movement {

    /**
     * Pins a player to where they are now.
     *
     * @param player the player
     */
    void freeze(@NotNull Player player);

    /**
     * Lets a player move again.
     *
     * @param player the player
     */
    void unfreeze(@NotNull Player player);

    /**
     * Returns whether this plugin froze the player.
     *
     * @param player the player
     * @return {@code true} while frozen
     */
    boolean isFrozen(@NotNull Player player);
}
