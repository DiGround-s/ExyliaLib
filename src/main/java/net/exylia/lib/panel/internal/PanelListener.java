package net.exylia.lib.panel.internal;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.ApiStatus;

/**
 * Routes a click in a panel window to the panel that drew it.
 *
 * <p>Every decision here is made against the {@link Session}, never against the
 * item the client says it clicked: a packet carries a slot number and a click
 * type, and the server already knows what it put in that slot. A slot the panel
 * did not draw is not a blank button — it is not a button.
 *
 * <p>One listener for the whole library rather than one per plugin: an inventory
 * event fires once, and the window's {@link PanelHolder} says whose panel it is.
 * Mirrors {@code ui/internal/MenuListener}, for the same reasons.
 */
@ApiStatus.Internal
public final class PanelListener implements Listener {

    /**
     * Handles a click in one of our windows.
     *
     * <p>At {@code HIGH} rather than {@code MONITOR}, because the event has to
     * be cancelled and a monitor listener must not change anything. Cancelled
     * before the panel acts: a handler that throws must not leave a button in
     * the player's hand.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Session session = sessionOf(event.getView().getTopInventory());
        if (session == null || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            // A click in their own inventory is not a button; refusing to move
            // items out of it would be someone else's rule, not this module's.
            return;
        }
        // Recorded before the click is routed, because a list row means
        // different things by button — right is delete, shift-left is copy — and
        // the routing itself carries only a slot number.
        session.observed(net.exylia.lib.ui.ClickKind.of(event.getClick()));
        session.click(event.getRawSlot());
    }

    /**
     * Stops a drag from writing over a panel's controls.
     *
     * <p>Refused outright rather than per slot: a panel has no input slots, so
     * every drag that touches the top inventory is one that would overwrite a
     * button.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Session session = sessionOf(event.getView().getTopInventory());
        if (session == null) {
            return;
        }
        int size = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < size) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Releases the session when the window goes.
     *
     * <p>The working copy is discarded rather than written: closing a panel is
     * not a way to save it by accident. Every other ending routes through the
     * same {@link Session#release()}, which is what keeps the cleanup path
     * single.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Session session = sessionOf(event.getView().getTopInventory());
        if (session != null) {
            session.release();
        }
    }

    private static Session sessionOf(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof PanelHolder holder
                ? holder.session()
                : null;
    }
}
