package net.exylia.lib.overlay.internal;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * What the overlay module sends, without saying how.
 *
 * <p>{@link OverlayPackets} is the one implementation that names PacketEvents.
 * Everything that decides <em>what</em> a player should see talks to this, so
 * those decisions can be exercised with a recording sink and no server.
 *
 * <p>The same object also does the intercepting, because both halves are the
 * same trick seen from two sides: what we write into the client and what we
 * stop the server from writing over it.
 */
public interface OverlaySink {

    /**
     * Draws one slot of a player's own inventory, client-side only.
     *
     * <p>Nothing on the server changes. The player's real item stays in the
     * slot, hidden, and comes back the moment the overlay stops covering it.
     *
     * @param viewer the player
     * @param index  the inventory index, as {@code OverlaySlots} numbers it
     * @param item   what to draw, or {@code null} to draw nothing
     */
    void slot(Player viewer, int index, @Nullable ItemStack item);

    /**
     * Forgets what is remembered about a player, on quit.
     *
     * @param player who left
     */
    void forget(java.util.UUID player);

    /** Stops listening. */
    void close();
}
