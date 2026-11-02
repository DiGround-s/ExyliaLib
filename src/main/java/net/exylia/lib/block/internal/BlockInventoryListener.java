package net.exylia.lib.block.internal;

import net.exylia.lib.block.ClickableBlock;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;

/**
 * Keeps hoppers, droppers and hopper minecarts away from registered blocks that
 * guard their inventory. Registered only once one does, see
 * {@link BlockRuntime#register}.
 *
 * @since 1.458.0
 */
public final class BlockInventoryListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (guarded(event.getSource()) || guarded(event.getDestination())) event.setCancelled(true);
    }

    /**
     * Whether an inventory is a guarded block's. A double chest's location is
     * the middle of its halves, which floors to one of them, and either half
     * finds the registration.
     */
    private static boolean guarded(Inventory inventory) {
        Location at = inventory.getLocation();
        if (at == null) return false;
        World world = at.getWorld();
        if (world == null) return false;
        ClickableBlock registered = BlockRuntime.at(world.getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        return registered != null && registered.protectsInventory();
    }
}
