package net.exylia.lib.internal;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the holder of a screen the library built, without touching the world.
 */
public final class Holders {

    private Holders() {
        throw new AssertionError("No instances.");
    }

    /**
     * The holder of a custom inventory, or {@code null} for any other.
     *
     * <p>Every library screen is a custom inventory, which has no location. A
     * block's inventory has one, and asking for its holder reads the block,
     * even without a snapshot: on Folia that throws off the block's region
     * thread, which is where an {@code InventoryCloseEvent} lands for a player
     * who is leaving or being teleported away from a chest.
     *
     * @param inventory the inventory, possibly {@code null}
     * @return its holder when it is a custom inventory
     */
    public static @Nullable InventoryHolder of(@Nullable Inventory inventory) {
        return inventory == null || inventory.getLocation() != null ? null : inventory.getHolder(false);
    }
}
