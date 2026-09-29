package net.exylia.lib.packet;

import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

/**
 * Where an item is drawn: the viewer's own inventory, or the upper part of a
 * window they opened.
 *
 * @param container whether the slot belongs to the opened window rather than
 *                  the viewer's own inventory, hotbar or cursor
 * @param holder    the opened window's holder, read when it opened; {@code null}
 *                  for the viewer's own inventory, and for a window nobody
 *                  opened through the server's API
 * @since 1.203.0
 */
public record ItemPlace(boolean container, @Nullable InventoryHolder holder) {

    /** The viewer's own inventory, hotbar, armour or cursor. */
    public static final ItemPlace OWN = new ItemPlace(false, null);

    /**
     * Whether the slot is in a window that is not a real container: a menu a
     * plugin drew, or a window with no holder the server knows of.
     *
     * <p>A chest, a barrel, a shulker box, a hopper, a furnace, a minecart
     * with a chest, a llama or another player's inventory are real.
     */
    public boolean isMenu() {
        if (!container) {
            return false;
        }
        return !(holder instanceof BlockState || holder instanceof DoubleChest || holder instanceof Entity);
    }
}
