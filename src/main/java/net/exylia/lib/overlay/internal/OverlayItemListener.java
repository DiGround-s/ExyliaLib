package net.exylia.lib.overlay.internal;

import net.exylia.lib.overlay.OverlayLock;
import net.exylia.lib.overlay.OverlaySlots;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.ClickKind;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

/**
 * What {@link OverlayPackets} does with packets, done with Bukkit events, for
 * the real-items fallback.
 *
 * <p>Two jobs. A drawn item must never leave the slot it was drawn in, while
 * the overlay is up or after; and a press on one runs its actions, exactly as
 * the packet sink would. The lock and world-press rules are the same
 * {@link OverlayClicks} decisions the packet sink makes.
 *
 * <p>Presses run a tick later on the player's thread, never inside the event:
 * an action may open a menu or move the player, and neither is safe in the
 * middle of an inventory click.
 */
final class OverlayItemListener implements Listener {

    // ------------------------------------------------------------------
    // Inventory
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
        ItemStack swapped = swappedWith(event, player);
        boolean tagged = OverlayItems.isTagged(event.getCurrentItem())
                || OverlayItems.isTagged(event.getCursor())
                || OverlayItems.isTagged(swapped);
        if (view == null) {
            if (tagged) {
                // Left behind by an overlay that is gone: it goes too.
                event.setCancelled(true);
                OverlayRuntime.atPlayer(player, () -> OverlayItems.purge(player));
            }
            return;
        }
        int index = event.getClickedInventory() instanceof PlayerInventory ? event.getSlot() : -1;
        boolean owned = index >= 0 && view.owns(index);
        boolean refuse = tagged;
        if (!refuse && !view.isSuspended()) {
            refuse = OverlayClicks.refuses(view.definition().lock(), scatters(event.getClick()),
                    isOwnScreen(player), index >= 0, owned);
        }
        if (refuse) {
            event.setCancelled(true);
        }
        if (owned && !view.isSuspended()) {
            ClickKind kind = ClickKind.of(event.getClick());
            if (kind != null) {
                later(view, () -> view.press(index, kind, null, null));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (OverlayItems.isTagged(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
        if (view == null || view.isSuspended()) {
            return;
        }
        boolean full = view.definition().lock() == OverlayLock.FULL;
        boolean ownScreen = isOwnScreen(player);
        for (int raw : event.getRawSlots()) {
            boolean mine = event.getView().getInventory(raw) instanceof PlayerInventory;
            if (!mine && !ownScreen) {
                continue;
            }
            int index = mine ? event.getView().convertSlot(raw) : -1;
            if (full || (index >= 0 && view.owns(index))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        boolean tagged = OverlayItems.isTagged(event.getItemDrop().getItemStack());
        OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
        if (view == null) {
            if (tagged) {
                event.getItemDrop().remove();
            }
            return;
        }
        if (tagged || (!view.isSuspended() && view.definition().lock() == OverlayLock.FULL)) {
            event.setCancelled(true);
        }
        int held = view.heldSlot();
        if (tagged && view.pressedAt(held) != null) {
            later(view, () -> view.press(held, ClickKind.DROP, null, null));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
        boolean tagged = OverlayItems.isTagged(event.getMainHandItem())
                || OverlayItems.isTagged(event.getOffHandItem());
        if (view == null) {
            if (tagged) {
                event.setCancelled(true);
                OverlayRuntime.atPlayer(player, () -> OverlayItems.purge(player));
            }
            return;
        }
        int held = view.heldSlot();
        boolean owned = view.owns(held) || view.owns(OverlaySlots.OFFHAND);
        if (tagged || owned || view.definition().lock() == OverlayLock.FULL) {
            event.setCancelled(true);
        }
        if (view.pressedAt(held) != null) {
            later(view, () -> view.press(held, ClickKind.SWAP, null, null));
        }
    }

    // ------------------------------------------------------------------
    // The world
    // ------------------------------------------------------------------

    /** Right and left clicks, in the air or on a block. Cancelled air clicks arrive too. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
        if (view == null) {
            return;
        }
        boolean offHand = event.getHand() == EquipmentSlot.OFF_HAND;
        int slot = offHand ? OverlaySlots.OFFHAND : view.heldSlot();
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        ClickKind kind = kindOf(left, player.isSneaking());
        OverlayClicks.WorldPress press = worldPress(view, player, slot, kind);
        if (press == OverlayClicks.WorldPress.PASS) {
            return;
        }
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setCancelled(true);
        if (press == OverlayClicks.WorldPress.REFUSE) {
            return;
        }
        Block block = event.getClickedBlock();
        // One press fires this for both hands, and a block press is followed
        // by the swing's air press: each is the same press, run once.
        if ((block == null || offHand) && view.repeatsWorldPress(kind)) {
            return;
        }
        view.markWorldPress(kind);
        later(view, () -> view.press(slot, kind, null, block));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
        if (view == null) {
            return;
        }
        int slot = event.getHand() == EquipmentSlot.OFF_HAND ? OverlaySlots.OFFHAND : view.heldSlot();
        ClickKind kind = kindOf(false, player.isSneaking());
        OverlayClicks.WorldPress press = worldPress(view, player, slot, kind);
        if (press == OverlayClicks.WorldPress.PASS) {
            return;
        }
        event.setCancelled(true);
        // The "at" twin of the same click is refused but not bound twice.
        // The client sends the "at" click and the plain one for one press.
        if (press == OverlayClicks.WorldPress.REFUSE || view.repeatsWorldPress(kind)) {
            return;
        }
        Entity target = event.getRightClicked();
        view.markWorldPress(kind);
        later(view, () -> view.press(slot, kind, target, null));
    }

    /** Its own handler list, so it is not delivered to the one above; the twin is swallowed there. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        onInteractEntity(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
        if (view == null) {
            return;
        }
        int slot = view.heldSlot();
        ClickKind kind = kindOf(true, player.isSneaking());
        OverlayClicks.WorldPress press = worldPress(view, player, slot, kind);
        if (press == OverlayClicks.WorldPress.PASS) {
            return;
        }
        event.setCancelled(true);
        if (press == OverlayClicks.WorldPress.REFUSE || view.repeatsWorldPress(kind)) {
            return;
        }
        Entity target = event.getEntity();
        view.markWorldPress(kind);
        later(view, () -> view.press(slot, kind, target, null));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (OverlayItems.isTagged(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (OverlayItems.isTagged(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (OverlayItems.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageItem(PlayerItemDamageEvent event) {
        if (OverlayItems.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Never outside the inventory
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(OverlayItems::isTagged);
    }

    /** The last net: whatever path a drawn item found into the world, it does not land. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (OverlayItems.isTagged(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static OverlayClicks.WorldPress worldPress(OverlayView view, Player player, int slot,
                                                       ClickKind kind) {
        ItemStack real = player.getInventory().getItem(slot);
        return OverlayClicks.worldPress(
                view.pressedAt(slot) != null,
                view.owns(slot),
                real == null || real.getType().isAir(),
                view.definition().emptyHand().bound(kind));
    }

    private static ClickKind kindOf(boolean left, boolean sneaking) {
        if (left) {
            return sneaking ? ClickKind.SHIFT_LEFT : ClickKind.LEFT;
        }
        return sneaking ? ClickKind.SHIFT_RIGHT : ClickKind.RIGHT;
    }

    /** The item a number key or the off-hand key would trade the clicked one with. */
    private static @Nullable ItemStack swappedWith(InventoryClickEvent event, Player player) {
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            return player.getInventory().getItem(event.getHotbarButton());
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return player.getInventory().getItemInOffHand();
        }
        return null;
    }

    /** Whether a click moves an item to a slot the server picks rather than the player. */
    private static boolean scatters(ClickType click) {
        return click.isShiftClick() || click == ClickType.NUMBER_KEY
                || click == ClickType.SWAP_OFFHAND || click == ClickType.DOUBLE_CLICK;
    }

    private static boolean isOwnScreen(Player player) {
        InventoryType type = player.getOpenInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    private static void later(OverlayView view, Runnable work) {
        Tasks.of(view.plugin()).runAtEntity(view.viewer(), work);
    }
}
