package net.exylia.lib.trade.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;

/**
 * Routes clicks, drags and closes in a trade window to its trade. Found
 * through the window's holder, never a player map: a chest opened on top is
 * not ours. Registered once by the library.
 */
public final class TradeListener implements Listener {

    private static TradeSession.Window window(InventoryView view) {
        return view.getTopInventory().getHolder(false) instanceof TradeSession.Window window ? window : null;
    }

    // LOWEST so a plugin reading the click afterwards sees it already refused.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        TradeSession.Window window = window(event.getView());
        if (window != null) {
            window.session().click(window, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        TradeSession.Window window = window(event.getView());
        if (window != null) {
            window.session().drag(window, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        TradeSession.Window window = window(event.getView());
        if (window != null) {
            window.session().closed(window, event);
        }
    }
}
