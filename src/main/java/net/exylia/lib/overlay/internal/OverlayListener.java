package net.exylia.lib.overlay.internal;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The server-side half of an overlay.
 *
 * <p>Two things a packet cannot do. Picking an item up is a real change to a
 * real inventory, so refusing it is a server decision; and a player who leaves
 * has to be forgotten, or the next player with their slot inherits a map entry.
 *
 * <p>Everything else an overlay refuses is refused as a packet, because the
 * client asking is the only evidence there is.
 */
public final class OverlayListener implements Listener {

    /**
     * Refuses a pickup for a player whose overlay says so.
     *
     * <p>The item would land in the real inventory, which the overlay is
     * covering: the player is handed something they cannot see, in a slot they
     * cannot reach, and finds it when they leave staff mode. Refusing leaves it
     * on the ground, where they can see it.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        if (refuses(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * The same refusal on the other event.
     *
     * <p>Both exist and neither implies the other: a plugin that uncancels one
     * has not uncancelled the other, and an item that arrives by a path that
     * only fires the second would otherwise get through.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && refuses(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        OverlayRuntime.forget(event.getPlayer().getUniqueId());
    }

    private static boolean refuses(Player player) {
        OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
        return view != null && !view.definition().pickup();
    }
}
