package net.exylia.lib.region.internal;

import net.exylia.lib.region.RegionChangeCause;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Single listener feeding accepted movement into the shared membership tracker. */
public final class RegionListener implements Listener {

    /**
     * Teleports inherit {@link PlayerMoveEvent}; handling only this registration avoids Bukkit
     * dispatching the same teleport through two handlers.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        World fromWorld = from.getWorld();
        World toWorld = to.getWorld();
        if (fromWorld == null || toWorld == null) return;
        int fromX = from.getBlockX();
        int fromY = from.getBlockY();
        int fromZ = from.getBlockZ();
        int toX = to.getBlockX();
        int toY = to.getBlockY();
        int toZ = to.getBlockZ();
        boolean worldChanged = !fromWorld.getUID().equals(toWorld.getUID());
        if (!worldChanged && fromX == toX && fromY == toY && fromZ == toZ) return;

        RegionChangeCause cause = event instanceof PlayerTeleportEvent
                ? RegionChangeCause.TELEPORT
                : worldChanged ? RegionChangeCause.WORLD_CHANGE : RegionChangeCause.MOVE;
        RegionRuntime.move(event.getPlayer(), toWorld.getUID(), toWorld.getName(),
                to.getX(), to.getY(), to.getZ(), cause);
    }

    /** Covers platform world transitions that do not produce a useful move destination. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Location location = event.getPlayer().getLocation();
        World world = location.getWorld();
        if (world == null) return;
        RegionRuntime.move(event.getPlayer(), world.getUID(), world.getName(),
                location.getX(), location.getY(), location.getZ(), RegionChangeCause.WORLD_CHANGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        RegionRuntime.initialize(event.getPlayer());
    }

    /** Quit is state disposal only; leaving the server does not synthesize region exits. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        VisualizationRuntime.forget(event.getPlayer().getUniqueId());
        RegionRuntime.forget(event.getPlayer().getUniqueId());
    }
}
