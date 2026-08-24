package net.exylia.lib.panel.internal;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What makes a window a panel.
 *
 * <p>The whole of the "no static map" rule lives in this class. A panel's state
 * hangs off the window it was opened with, so asking "does this player have a
 * panel open" is really asking "is the window they are looking at one of ours" —
 * which is a different question, and the right one. A player with a chest open
 * on top of a panel is looking at the chest, and the holder says so.
 *
 * <p>Mirrors {@code ui/internal/MenuHolder}, which exists for the same reason.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class PanelHolder implements InventoryHolder {

    private Session session;
    private Inventory inventory;

    /** Binds the window to its session, once, as it is opened. */
    public void bind(Session session, Inventory inventory) {
        this.session = session;
        this.inventory = inventory;
    }

    /** The panel this window is showing, or {@code null} before it is bound. */
    public @Nullable Session session() {
        return session;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
