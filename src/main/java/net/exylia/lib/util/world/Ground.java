package net.exylia.lib.util.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.NotNull;

/**
 * The ground under a point: where something placed near a player stands, instead of at the player's own height
 * (a player who jumps or flies would otherwise leave mobs and effects hanging in the air).
 *
 * <pre>{@code
 * Location from = Ground.below(player.getLocation().add(4, 0, 7));        // a mob that walks in
 * Location frost = Ground.below(spot, 2, 12).add(0, 0.02, 0);              // a patch on the floor
 * }</pre>
 *
 * Reads blocks, so it runs on the thread that owns the point (Folia); off it, or in an unloaded chunk, it hands the
 * point back unchanged rather than loading anything.
 *
 * @since 1.667.0
 */
public final class Ground {

    private Ground() {
    }

    /** {@link #below(Location, int, int)} looking one block up and five down. */
    public static @NotNull Location below(@NotNull Location at) {
        return below(at, 1, 5);
    }

    /**
     * A copy of {@code at} lowered or raised onto the top of the first solid block found from {@code up} blocks above
     * it down to {@code down} blocks below, with room to stand over it (slabs, farmland and paths at their real
     * height); {@code at} itself when there is none in that range.
     */
    public static @NotNull Location below(@NotNull Location at, int up, int down) {
        World world = at.getWorld();
        if (world == null || !Bukkit.isOwnedByCurrentRegion(at)
                || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return at.clone();
        int x = at.getBlockX(), z = at.getBlockZ(), top = at.getBlockY() + Math.max(0, up);
        int bottom = Math.max(world.getMinHeight(), at.getBlockY() - Math.max(0, down));
        for (int y = Math.min(top, world.getMaxHeight() - 2); y >= bottom; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.isPassable() || !block.getRelative(BlockFace.UP).isPassable()) continue;
            Location out = at.clone();
            out.setY(y + block.getBoundingBox().getHeight());
            return out;
        }
        return at.clone();
    }
}
