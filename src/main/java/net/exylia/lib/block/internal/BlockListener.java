package net.exylia.lib.block.internal;

import net.exylia.lib.block.BlockButton;
import net.exylia.lib.block.BlockClick;
import net.exylia.lib.block.ClickableBlock;
import net.exylia.lib.debug.Debug;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;

/**
 * The one listener behind every registered block on the server.
 *
 * <p>Every handler leaves immediately when nothing is registered where the
 * event happened, which on a server with no clickable blocks is one map lookup
 * and no allocation.
 *
 * @since 1.110.0
 */
public final class BlockListener implements Listener {

    /**
     * High rather than monitor: the click has to be cancelled before the
     * material acts on it, and a crate drawn on a barrel that opens its
     * inventory is the bug this exists to prevent.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        BlockButton button = switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK -> BlockButton.RIGHT;
            case LEFT_CLICK_BLOCK -> BlockButton.LEFT;
            default -> null;
        };
        if (button == null) return;

        Block block = event.getClickedBlock();
        ClickableBlock registered = BlockRuntime.at(block);
        if (registered == null) return;

        if (registered.cancelsVanilla()) event.setCancelled(true);

        Player player = event.getPlayer();
        BlockRuntime.Position position = new BlockRuntime.Position(
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        if (BlockRuntime.isRepeat(player.getUniqueId(), position, button)) return;

        try {
            registered.fire(new BlockClick(player, block, button, player.isSneaking()));
        } catch (Throwable error) {
            // A handler that throws must not take the interact event down with
            // it, and with it every plugin behind this one in the chain.
            Debug.of(registered.plugin()).error("Clickable block handler failed at "
                    + block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ(), error);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ClickableBlock registered = BlockRuntime.at(event.getBlock());
        if (registered != null && registered.protectedBlock()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        ClickableBlock registered = BlockRuntime.at(event.getBlock());
        if (registered != null && registered.protectedBlock()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        spare(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        spare(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesRegistered(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesRegistered(event.getBlocks())) event.setCancelled(true);
    }

    /**
     * Takes the protected blocks out of an explosion rather than cancelling it,
     * so one crate in range does not save the rest of the street.
     */
    private static void spare(List<Block> blocks) {
        blocks.removeIf(block -> {
            ClickableBlock registered = BlockRuntime.at(block);
            return registered != null && registered.protectedBlock();
        });
    }

    private static boolean movesRegistered(List<Block> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            ClickableBlock registered = BlockRuntime.at(blocks.get(index));
            if (registered != null && registered.protectedBlock()) return true;
        }
        return false;
    }
}
