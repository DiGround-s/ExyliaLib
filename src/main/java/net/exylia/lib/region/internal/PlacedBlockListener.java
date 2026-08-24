package net.exylia.lib.region.internal;

import net.exylia.lib.region.RegionSnapshot;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.List;

/**
 * Records what a player actually built, for the regions that asked to know.
 *
 * <h2>Why MONITOR</h2>
 * Both handlers run after every consumer has had its say and only for events nobody
 * cancelled, so the record states what happened rather than what was attempted. It
 * also puts the break handler behind the consumer's {@code player_build_only} check:
 * the block is still recorded when that check reads it, and forgotten immediately
 * after.
 *
 * <p>Neither handler ever cancels anything. The library states what a region
 * declares; the consumer decides what to do about it.
 */
public final class PlacedBlockListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        Block block = event.getBlock();
        List<RegionSnapshot> regions = regionsAt(block);
        for (int index = 0; index < regions.size(); index++) {
            RegionSnapshot region = regions.get(index);
            if (!PlacedBlockRuntime.tracks(region)) continue;
            PlacedBlockRuntime.placed(region, event.getPlayer().getUniqueId(),
                    block.getType(), block.getX(), block.getY(), block.getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        Block block = event.getBlock();
        List<RegionSnapshot> regions = regionsAt(block);
        for (int index = 0; index < regions.size(); index++) {
            PlacedBlockRuntime.untrack(regions.get(index).id(),
                    block.getX(), block.getY(), block.getZ());
        }
    }

    /**
     * The regions containing a block.
     *
     * <p>The block's minimum corner, not its centre: region shapes are minimum
     * inclusive and maximum exclusive, and {@code Cuboid.blocks} builds them from
     * inclusive block corners, so the corner is the coordinate that agrees with the
     * block the player sees.
     */
    private static List<RegionSnapshot> regionsAt(Block block) {
        return RegionRuntime.query(block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ());
    }
}
