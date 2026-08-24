package net.exylia.lib.region.internal;

import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.SelectionState;
import net.exylia.lib.region.WorldIdentity;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Single deterministic event router for all plugins' region selection sessions.
 *
 * <p>Three gestures, and the order they are checked in is the whole listener:
 * a sneaking left-click confirms, a left-click sets the first corner, a
 * right-click sets the second. Confirmation is tested first because a sneaking
 * left-click on a block is also a left-click on a block, and reading it as a
 * corner would make the gesture that accepts the box also move it.
 */
public final class SelectionListener implements Listener {

    /** Routes selector clicks: confirm, first corner, second corner. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        boolean leftClick = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;
        if (!leftClick && action != Action.RIGHT_CLICK_BLOCK) return;

        SelectionRuntime.Session session = SelectionRuntime.routed(event.getPlayer().getUniqueId());
        if (session == null) return;
        Material used = event.getMaterial();
        if (used != session.options().selectorMaterial()) return;

        // Accepting the box does not need a block under the cursor: an admin
        // standing in the middle of what they just outlined has nothing in
        // reach, and asking them to walk to a wall to say yes is the kind of
        // friction that makes people stop using the tool.
        if (leftClick && event.getPlayer().isSneaking()
                && session.state() == SelectionState.AWAITING_CONFIRMATION) {
            if (session.options().cancelInteractions()) event.setCancelled(true);
            SelectionRuntime.confirm(event.getPlayer().getUniqueId());
            return;
        }

        if (action == Action.LEFT_CLICK_AIR) return;

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
