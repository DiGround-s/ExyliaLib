package net.exylia.lib.block;

import net.exylia.lib.block.internal.PlacedBlockTracker;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Objects;

/**
 * Whether a block anywhere in the world was put there by a player, remembered
 * across restarts.
 *
 * <pre>{@code
 * PlacedBlocks.track(this, List.of(Material.SUGAR_CANE, Material.DIAMOND_ORE));
 *
 * @EventHandler(ignoreCancelled = true)
 * public void onBreak(BlockBreakEvent event) {
 *     if (PlacedBlocks.placedByPlayer(event.getBlock())) return; // no reward for place-and-break
 *     reward(event.getPlayer());
 * }
 * }</pre>
 *
 * <h2>Why the chunk and not a map</h2>
 * Anti-farming needs the answer long after the placement: a column of sugar
 * cane placed, left for an hour and broken, or ore placed before a restart. A
 * map in memory forgets on quit or restart and grows with every builder; a
 * table is a database write per placement. The record is kept in the chunk's
 * persistent data, so it is saved and loaded with the blocks it describes and
 * costs nothing while the chunk is unloaded.
 *
 * <h2>What is recorded</h2>
 * Only the materials some plugin asked for, so a builder placing stone costs
 * one set lookup per block. A tracked block a player places is recorded, a
 * piston moves its record with it, and breaking it forgets it — after every
 * other listener has asked, so a break handler at any priority below
 * {@code MONITOR} still sees it as placed. A block that leaves another way (an
 * explosion, burning, growing over) keeps its record: the answer errs on
 * "placed", which denies a reward rather than paying a farm. Each chunk keeps
 * at most 4096 positions and forgets the oldest past that.
 *
 * <h2>Not the region record</h2>
 * {@code PluginRegions.placedByPlayer} answers the same question inside regions
 * declaring {@code player_build_only}, and lives exactly as long as the region.
 * That lifetime is the point there — an arena re-registered for the next match
 * must start clean — so the two records stay apart.
 *
 * <h2>Threads</h2>
 * Ask from the thread that owns the block, which is where its events already
 * run. Registrations are released with their plugin.
 *
 * @since 1.163.0
 */
public final class PlacedBlocks {

    private PlacedBlocks() {
    }

    /**
     * Starts recording the placements of these materials for a plugin,
     * replacing whatever it asked for before.
     *
     * @param plugin    the plugin asking
     * @param materials the materials whose placement it needs to know about
     */
    public static void track(@NotNull Plugin plugin, @NotNull Collection<Material> materials) {
        PlacedBlockTracker.track(Objects.requireNonNull(plugin, "plugin").getName(),
                Objects.requireNonNull(materials, "materials"));
    }

    /**
     * Whether a player placed this block, for a tracked material.
     *
     * @param block the block
     * @return {@code true} when a player placed a tracked block at this position
     *         and it has not been broken since
     */
    public static boolean placedByPlayer(@NotNull Block block) {
        return PlacedBlockTracker.placed(Objects.requireNonNull(block, "block"));
    }

    /** Forgets what a plugin asked to track. What was recorded stays. */
    public static void release(@NotNull String pluginName) {
        PlacedBlockTracker.release(pluginName);
    }
}
