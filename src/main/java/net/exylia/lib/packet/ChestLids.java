package net.exylia.lib.packet;

import net.exylia.lib.packet.internal.LidPackets;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * Opens and closes a container's lid for some players, without opening it.
 *
 * <pre>{@code
 * ChestLids.open(watchers, crate.getLocation());
 * // ... the reveal plays ...
 * ChestLids.close(watchers, crate.getLocation());
 * }</pre>
 *
 * <p>The lid a crate lifts while its reward rises out. Nothing changes on the
 * server: nobody is looking inside, hoppers and comparators see a closed
 * chest, and a player not in the list sees nothing move. The sound is not
 * played; pair it with {@code block.chest.open}.
 *
 * <p>Chests, trapped chests, ender chests and shulker boxes swing through a
 * block event and need PacketEvents. A barrel has no lid to swing and is
 * drawn open instead, which is plain Bukkit. A client that reloads the chunk
 * shows the block as it really is.
 *
 * <p>Reads the block, so it is called from the thread that owns the location;
 * a sequence step already is.
 *
 * @since 1.272.0
 */
public final class ChestLids {

    private ChestLids() {
        throw new AssertionError("No instances.");
    }

    /**
     * Opens the lid at a block for these players.
     *
     * @param viewers who sees it open
     * @param block   any location inside the block
     * @return whether that block has a lid that could be opened here
     */
    public static boolean open(@NotNull Collection<? extends Player> viewers, @NotNull Location block) {
        return set(viewers, block, true);
    }

    /**
     * Closes it again.
     *
     * @param viewers who sees it close
     * @param block   any location inside the block
     * @return whether that block has a lid that could be closed here
     */
    public static boolean close(@NotNull Collection<? extends Player> viewers, @NotNull Location block) {
        return set(viewers, block, false);
    }

    /**
     * Opens or closes the lid at a block for these players.
     *
     * @param viewers who sees it
     * @param block   any location inside the block
     * @param open    whether it ends up open
     * @return whether that block has a lid that could be moved here
     */
    public static boolean set(@NotNull Collection<? extends Player> viewers, @NotNull Location block,
                              boolean open) {
        if (viewers.isEmpty() || block.getWorld() == null) {
            return false;
        }
        Block at = block.getBlock();
        Material type = at.getType();
        if (type == Material.BARREL) {
            BlockData data = at.getBlockData().clone();
            if (data instanceof org.bukkit.block.data.type.Barrel barrel) {
                barrel.setOpen(open);
                for (Player viewer : viewers) {
                    if (viewer.isOnline() && block.getWorld().equals(viewer.getWorld())) {
                        viewer.sendBlockChange(at.getLocation(), barrel);
                    }
                }
                return true;
            }
            return false;
        }
        if (!swings(type) || !Packets.isAvailable()) {
            return false;
        }
        LidPackets.send(List.<Player>copyOf(viewers), at.getX(), at.getY(), at.getZ(), open);
        return true;
    }

    /** Whether the block's lid is swung by a block event. */
    static boolean swings(@NotNull Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST
                || type == Material.ENDER_CHEST || type.name().endsWith("SHULKER_BOX");
    }
}
