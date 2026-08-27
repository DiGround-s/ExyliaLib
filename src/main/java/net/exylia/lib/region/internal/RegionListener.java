package net.exylia.lib.region.internal;

import net.exylia.lib.region.RegionChangeCause;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Single listener feeding accepted movement into the shared membership tracker. */
public final class RegionListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        handle(event.getPlayer(), event.getFrom(), event.getTo(), RegionChangeCause.MOVE);
    }

    /**
     * Teleports do not reach {@link #onMove}.
     *
     * <p>{@link PlayerTeleportEvent} extends {@link PlayerMoveEvent} but declares
     * its own {@code HandlerList}, and Bukkit dispatches an event only to the
     * list its concrete class returns. A player who arrives by teleport would
     * otherwise keep the regions of wherever they left, until their first step.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        handle(event.getPlayer(), event.getFrom(), event.getTo(), RegionChangeCause.TELEPORT);
    }

    /** Portal travel is a third handler list again, for the same reason. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        handle(event.getPlayer(), event.getFrom(), event.getTo(), RegionChangeCause.TELEPORT);
    }

    /** A respawn moves the player without any move or teleport event at all. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        handle(event.getPlayer(), event.getPlayer().getLocation(), event.getRespawnLocation(),
                RegionChangeCause.TELEPORT);
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

    /**
     * Publishes a destination, skipping the step that did not leave its block.
     *
     * <p>A destination in another world is reported as a world change whatever
     * moved the player there: the second notification the platform sends for it
     * finds the membership already current and stays silent.
     */
    private static void handle(Player player, Location from, Location to,
                               RegionChangeCause cause) {
        if (to == null) return;
        World toWorld = to.getWorld();
        if (toWorld == null) return;

        World fromWorld = from == null ? null : from.getWorld();
        boolean worldChanged = fromWorld == null || !fromWorld.getUID().equals(toWorld.getUID());
        if (!worldChanged
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        RegionRuntime.move(player, toWorld.getUID(), toWorld.getName(),
                to.getX(), to.getY(), to.getZ(),
                worldChanged && cause == RegionChangeCause.MOVE
                        ? RegionChangeCause.WORLD_CHANGE
                        : cause);
    }
}
