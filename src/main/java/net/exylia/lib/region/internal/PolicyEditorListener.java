package net.exylia.lib.region.internal;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * One listener for every plugin's policy editors; the window's holder says
 * whose editor it is.
 *
 * <p>Every click is cancelled, the viewer's own inventory included: the editor
 * is a screen, not a container.
 */
@ApiStatus.Internal
public final class PolicyEditorListener implements Listener {

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        PolicyEditorHolder holder = PolicyEditorHolder.of(event.getView().getTopInventory());
        if (holder == null || !(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            holder.click(viewer, event.getSlot(), event.isRightClick());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (PolicyEditorHolder.of(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    /** Closing the window, leaving included, discards the working copy. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        // A chunk unloading closes block windows, never an editor, and asking
        // such a window for its holder loads the chunk being unloaded.
        if (event.getReason() == InventoryCloseEvent.Reason.UNLOADED) {
            return;
        }
        PolicyEditorHolder holder = PolicyEditorHolder.of(event.getInventory());
        if (holder != null) {
            holder.closed();
        }
    }
}
