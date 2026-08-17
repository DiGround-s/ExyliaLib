package net.exylia.lib.ui.internal;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as one of ours, and says whose.
 *
 * <p>How a click finds its session. The alternative — a map from player to open
 * menu — answers the wrong question the moment a player opens a chest while a
 * menu is on screen, or a plugin opens a second inventory on top of the first.
 * The holder travels with the window itself, so there is nothing to keep in
 * sync and nothing to leak.
 *
 * <p>Set after construction because the inventory needs the holder and the
 * session needs the inventory.
 */
final class MenuHolder implements InventoryHolder {

    private Session session;
    private Inventory inventory;

    void bind(Session session, Inventory inventory) {
        this.session = session;
        this.inventory = inventory;
    }

    /** The menu this window is showing, or {@code null} before it is bound. */
    Session session() {
        return session;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
