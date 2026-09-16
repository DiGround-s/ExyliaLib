package net.exylia.lib.camera.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The server-side half of a camera.
 *
 * <p>Three things a packet cannot tell the module. A player who leaves has to be
 * forgotten, or their entry outlives them and the next shot they ask for is
 * refused because the server still believes they are filming. A player who dies
 * is on a respawn screen looking through a camera that is about to be sent
 * positions for a body that is not there. A player who changes world has left
 * the entity the camera was spawned in, so their client dropped it and there is
 * nothing left to look through.
 *
 * <p>Every one of them ends the same way, which is the point: whatever happens
 * to a player being filmed, the shot ends and their own eyes come back.
 */
public final class CameraListener implements Listener {

    /**
     * Ends the shot when its subject leaves.
     *
     * <p>Nothing reaches their client any more, so this is bookkeeping rather
     * than a packet — but it is the bookkeeping that decides whether they can
     * use an emote again when they come back.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        CameraRuntime.stop(event.getPlayer());
    }

    /** Ends the shot when its subject dies, before the respawn screen. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        CameraRuntime.stop(event.getEntity());
    }

    /**
     * Ends the shot when its subject leaves the world it was filmed in.
     *
     * <p>The camera is an entity in the world they left: their client threw it
     * away with the rest of that world, and a view restored afterwards would be
     * restored from nothing.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        CameraRuntime.stop(event.getPlayer());
    }
}
