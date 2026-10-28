package net.exylia.lib.guide.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.Location;

/**
 * The three things a packet cannot tell the module.
 *
 * <p>A player who leaves takes their client with them, so their sessions are
 * forgotten without a packet. A player who changes world, or respawns, has a
 * client that threw away every entity it was shown; guidance is not over
 * because of either, so it is drawn again on their next tick where they now
 * stand. So is a long teleport inside one world: the client unloads the
 * chunks the pieces stood in and they are gone without a packet.
 */
final class GuideListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        GuideRuntime.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        GuideRuntime.clientReset(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != null && from.getWorld().equals(to.getWorld())
                && GuideGeometry.isLongTeleport(from.getX(), from.getZ(), to.getX(), to.getZ(),
                Math.min(event.getPlayer().getClientViewDistance(), to.getWorld().getViewDistance()))) {
            GuideRuntime.teleported(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        GuideRuntime.clientReset(event.getPlayer());
    }
}
