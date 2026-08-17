package net.exylia.lib.region.internal;

import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.WorldIdentity;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Single deterministic event router for all plugins' region selection sessions. */
public final class SelectionListener implements Listener {

    /** Captures left and right block clicks made with the active session's selector material. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;

        SelectionRuntime.Session session = SelectionRuntime.routed(event.getPlayer().getUniqueId());
        if (session == null) return;
        Material used = event.getMaterial();
        if (used != session.options().selectorMaterial()) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        if (session.options().cancelInteractions()) event.setCancelled(true);
        SelectionRuntime.select(event.getPlayer().getUniqueId(), action == Action.LEFT_CLICK_BLOCK,
                new BlockPosition(WorldIdentity.from(block.getWorld()),
                        block.getX(), block.getY(), block.getZ()));
    }

    /** Cancels the leaving player's globally unique active selector. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        SelectionRuntime.Session session =
                SelectionRuntime.routed(event.getPlayer().getUniqueId());
        if (session != null) session.cancel();
    }
}
