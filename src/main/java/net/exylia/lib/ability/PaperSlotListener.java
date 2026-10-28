package net.exylia.lib.ability;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Paper's slot event, which sees an item replaced in place: {@code /clear}
 * then {@code /give}, or a kit plugin writing the same slot. None of the
 * other refresh points fire for that, so the old item's abilities stayed on.
 *
 * <p>Loaded by name only on Paper: Spigot never resolves this class.
 */
final class PaperSlotListener implements Listener {

    /** Armour is 36-39 and the offhand 40 in a player's inventory. */
    private static final int FIRST_WORN = 36;
    private static final int OFFHAND = 40;

    private final PluginAbilities abilities;

    PaperSlotListener(PluginAbilities abilities) {
        this.abilities = abilities;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlot(PlayerInventorySlotChangeEvent event) {
        if (counts(event.getSlot(), event.getPlayer().getInventory().getHeldItemSlot())) {
            // Coalesced: a kit writing forty slots is one refresh next tick.
            abilities.refreshSoon(event.getPlayer());
        }
    }

    /** Whether a slot of the player's inventory is one abilities are read from. */
    static boolean counts(int slot, int held) {
        return slot == held || (slot >= FIRST_WORN && slot <= OFFHAND);
    }
}
