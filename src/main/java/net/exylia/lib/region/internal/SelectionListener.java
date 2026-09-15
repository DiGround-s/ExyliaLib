package net.exylia.lib.region.internal;

import net.exylia.lib.packet.internal.PacketRuntime;
import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.SelectionState;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.task.Tasks;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/**
 * Single deterministic event router for all plugins' region selection sessions.
 *
 * <p>Three gestures, and the order they are checked in is the whole listener:
 * a sneaking left-click confirms, a left-click sets the first corner, a
 * right-click sets the second. Confirmation is tested first because a sneaking
 * left-click on a block is also a left-click on a block, and reading it as a
 * corner would make the gesture that accepts the box also move it.
 *
 * <h2>A selector the server does not have</h2>
 * A virtual selector is recognised by the slot it is drawn in rather than by a
 * material, because the server holds whatever was really there. Every use of
 * that slot is cancelled, and nothing can be moved in or out of it, so the real
 * item underneath is never placed, eaten or swapped while it looks like the
 * tool. Drops and hand swaps from the world never reach an event with an empty
 * hand, so those are swallowed by the packet module instead.
 */
public final class SelectionListener implements Listener {

    /** Routes selector clicks: confirm, first corner, second corner. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.PHYSICAL) return;

        Player player = event.getPlayer();
        SelectionRuntime.Session session = SelectionRuntime.routed(player.getUniqueId());
        if (session == null) return;
        boolean virtual = session.options().virtualSelector() && holdsOverlay(player);
        if (virtual) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        } else if (event.getMaterial() != session.options().selectorMaterial()) {
            return;
        }

        boolean leftClick = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;
        if (!leftClick && action != Action.RIGHT_CLICK_BLOCK) return;

        // Accepting the box does not need a block under the cursor: an admin
        // standing in the middle of what they just outlined has nothing in
        // reach, and asking them to walk to a wall to say yes is the kind of
        // friction that makes people stop using the tool.
        if (leftClick && player.isSneaking()
                && session.state() == SelectionState.AWAITING_CONFIRMATION) {
            if (session.options().cancelInteractions()) event.setCancelled(true);
            SelectionRuntime.confirm(player.getUniqueId());
            return;
        }

        if (action == Action.LEFT_CLICK_AIR) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        if (session.options().cancelInteractions()) event.setCancelled(true);
        SelectionRuntime.select(player.getUniqueId(), action == Action.LEFT_CLICK_BLOCK,
                new BlockPosition(WorldIdentity.from(block.getWorld()),
                        block.getX(), block.getY(), block.getZ()));
    }

    /**
     * Keeps the virtual selector's slot out of every inventory click.
     *
     * <p>Creative is the dangerous one: its clicks tell the server what a slot
     * now holds, so a client that picked up the drawn selector could set it
     * into any slot — or throw it — and have it become real. A selector in a
     * creative click is refused for anyone not holding a real one.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event instanceof InventoryCreativeEvent creative && SelectorWand.isWand(creative.getCursor())
                && !holdsRealSelector(player)) {
            event.setCancelled(true);
            return;
        }
        int slot = PacketRuntime.overlaySlot(player.getUniqueId());
        if (slot < 0) return;
        boolean onOverlay = event.getClickedInventory() instanceof PlayerInventory clicked
                && player.equals(clicked.getHolder()) && event.getSlot() == slot;
        if (onOverlay || event.getHotbarButton() == slot) {
            // Cancelled clicks are sent back by the server, and the overlay is
            // drawn over what it sends.
            event.setCancelled(true);
        }
    }

    /** Keeps a drag from spreading items into the virtual selector's slot. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = PacketRuntime.overlaySlot(player.getUniqueId());
        if (slot < 0) return;
        InventoryView view = event.getView();
        for (int raw : event.getRawSlots()) {
            if (view.getInventory(raw) instanceof PlayerInventory && view.convertSlot(raw) == slot) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Draws the virtual selector again once a container closes.
     *
     * <p>A container's packets carry the player's inventory under its own
     * window, which the overlay does not rewrite, and closing it sends nothing
     * back. A tick later the player is looking at their own inventory again.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || PacketRuntime.overlaySlot(player.getUniqueId()) < 0) return;
        Plugin library = RegionRuntime.library();
        if (library == null) return;
        Tasks.of(library).runAtEntityLater(player, 1L, player::updateInventory);
    }

    /** Cancels the leaving player's globally unique active selector. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        SelectionRuntime.Session session =
                SelectionRuntime.routed(event.getPlayer().getUniqueId());
        if (session != null) session.cancel();
    }

    private static boolean holdsOverlay(Player player) {
        int slot = PacketRuntime.overlaySlot(player.getUniqueId());
        return slot >= 0 && slot == player.getInventory().getHeldItemSlot();
    }

    private static boolean holdsRealSelector(Player player) {
        SelectionRuntime.Session session = SelectionRuntime.routed(player.getUniqueId());
        return session != null && session.options().giveSelector() && !session.options().virtualSelector();
    }
}
