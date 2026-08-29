package net.exylia.lib.packet;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Watching a container without opening it.
 *
 * <p>Opens a mirror of the source inventory to the viewer: no chest lid, no
 * sound, nothing the owner can notice. The mirror follows the source while it
 * is open, and when {@code editable} the viewer's changes are written back.
 *
 * <p>Plain Bukkit — works without PacketEvents.
 *
 * <h2>Limits</h2>
 * The mirror is polled once a tick, so a change on the source shows up a tick
 * late. A source that is not a multiple of nine slots (a player inventory)
 * is shown padded to the next row. The source's own view logic — a shulker
 * box that refuses another shulker box, a furnace's fuel slot — is not
 * enforced on an editable mirror.
 *
 * @since 1.75.0
 */
public interface SilentContainer {

    /**
     * Opens a mirror of {@code source} to {@code viewer}.
     *
     * <p>Call from the viewer's thread, as any inventory open.
     *
     * @param viewer   the player
     * @param source   the inventory to mirror
     * @param title    the window title
     * @param editable whether the viewer's edits are written to the source
     * @return the open view, or {@code null} when the client refused it
     */
    @Nullable InventoryView open(@NotNull Player viewer, @NotNull Inventory source,
                                 @NotNull Component title, boolean editable);
}
