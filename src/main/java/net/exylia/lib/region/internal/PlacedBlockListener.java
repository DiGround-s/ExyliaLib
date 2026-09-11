package net.exylia.lib.region.internal;

import net.exylia.lib.region.RegionSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Records what a player actually built, for the regions that asked to know.
 *
 * <h2>What counts as built</h2>
 * A block a player places, both halves of a bed or a door included; the fluid a
 * player pours, unless it only waterlogs a block that was already there; a block
 * that forms where there was air or a fluid, such as cobblestone from lava or ice
 * from water; and a recorded block that falls, at the position it lands on. A record
 * is forgotten when its block is broken, blown up, scooped into a bucket or starts
 * to fall. Anything else in the region is the map's.
 *
 * <h2>Why MONITOR</h2>
 * Every handler runs after every consumer has had its say and only for events nobody
 * cancelled, so the record states what happened rather than what was attempted. It
 * also puts the break handler behind the consumer's {@code player_build_only} check:
 * the block is still recorded when that check reads it, and forgotten immediately
 * after.
 *
 * <p>No handler ever cancels anything. The library states what a region declares;
 * the consumer decides what to do about it.
 */
public final class PlacedBlockListener implements Listener {

    /**
     * Marks a falling block that left a recorded position. On the entity rather than
     * in a set of ids, so a falling block that breaks into an item takes it along.
     */
    private final NamespacedKey fromPlayer;

    public PlacedBlockListener(@NotNull Plugin plugin) {
        this.fromPlayer = new NamespacedKey(plugin, "placed_block_falling");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        Block main = event.getBlock();
        placed(main, event.getPlayer().getUniqueId(), main.getType());
        if (!(event instanceof BlockMultiPlaceEvent multi)) return;
        // Only the block the player placed is owed back: re-giving every half would
        // hand back two doors for one.
        for (BlockState state : multi.getReplacedBlockStates()) {
            if (state.getX() == main.getX() && state.getY() == main.getY()
                    && state.getZ() == main.getZ()) continue;
            Block half = state.getBlock();
            placed(half, null, half.getType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        // The event fires before the change, so this is still the block being
        // replaced. Air or a fluid means the formed block is new; anything else is a
        // map block turning into another, like copper weathering, and stays the map's.
        Block block = event.getBlock();
        if (block.isEmpty() || block.isLiquid()) owned(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        // Waterlogging fills a block that is already there; recording it would hand a
        // map slab to whoever poured water into it.
        Block block = event.getBlock();
        if (block.getBlockData() instanceof Waterlogged) return;
        owned(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        // Scooping water out of a waterlogged block leaves the block itself in place.
        Block block = event.getBlock();
        if (block.getBlockData() instanceof Waterlogged) return;
        forget(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        forgetAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        forgetAll(event.blockList());
    }

    /**
     * Carries a record along with a block that falls.
     *
     * <p>The same event reports both ends of the fall. When it starts, the position
     * still holds the falling material; when it lands, the position holds whatever the
     * block is about to replace. Comparing materials tells them apart even for a block
     * that leaves water behind, which a check for air would miss.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFall(EntityChangeBlockEvent event) {
        if (!PlacedBlockRuntime.tracking()) return;
        if (!(event.getEntity() instanceof FallingBlock falling)) return;
        Block block = event.getBlock();
        PersistentDataContainer data = falling.getPersistentDataContainer();
        if (block.getType() == falling.getBlockData().getMaterial()) {
            if (forget(block)) data.set(fromPlayer, PersistentDataType.BYTE, (byte) 1);
        } else if (data.has(fromPlayer, PersistentDataType.BYTE)) {
            placed(block, null, event.getTo());
        }
    }

    private static void placed(Block block, @Nullable UUID playerId, Material material) {
        List<RegionSnapshot> regions = regionsAt(block);
        for (int index = 0; index < regions.size(); index++) {
            RegionSnapshot region = regions.get(index);
            if (!PlacedBlockRuntime.tracks(region)) continue;
            PlacedBlockRuntime.placed(region, playerId, material,
                    block.getX(), block.getY(), block.getZ());
        }
    }

    private static void owned(Block block) {
        List<RegionSnapshot> regions = regionsAt(block);
        for (int index = 0; index < regions.size(); index++) {
            RegionSnapshot region = regions.get(index);
            if (!PlacedBlockRuntime.tracks(region)) continue;
            PlacedBlockRuntime.owned(region, block.getX(), block.getY(), block.getZ());
        }
    }

    /** Forgets one position in every region containing it, returning whether any had it. */
    private static boolean forget(Block block) {
        boolean recorded = false;
        List<RegionSnapshot> regions = regionsAt(block);
        for (int index = 0; index < regions.size(); index++) {
            recorded |= PlacedBlockRuntime.untrack(regions.get(index).id(),
                    block.getX(), block.getY(), block.getZ());
        }
        return recorded;
    }

    private static void forgetAll(List<Block> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            forget(blocks.get(index));
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
