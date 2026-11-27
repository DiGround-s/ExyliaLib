package net.exylia.lib.packet.area;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The shapes a locked area is fenced off with, as blocks to hand to {@link AreaLooks#show}: a wall
 * along its border, a fence joined post to post, and an entrance with a portcullis in the edge that
 * faces the way in. Boxes are given as block corners, inclusive; nothing here touches the world.
 *
 * @since 1.676.0
 */
public final class AreaWalls {

    private AreaWalls() {
    }

    /**
     * Where an area is entered: the middle of one of its edges.
     *
     * @param edge    the block coordinate of that edge, on the X axis when {@code onX}, else on Z
     * @param mid     the middle of the edge, along the other axis
     * @param onX     whether the edge is a line of constant X
     * @param outward which way is out of the area across the edge: -1 or 1
     */
    public record Entrance(int edge, int mid, boolean onX, int outward) {

        /** The block X of the entrance's middle. */
        public int x() {
            return onX ? edge : mid;
        }

        /** The block Z of the entrance's middle. */
        public int z() {
            return onX ? mid : edge;
        }

        /** A point just outside the entrance, {@code up} blocks above the floor block {@code floorY}. */
        public @NotNull Location outside(@Nullable World world, double floorY, double up) {
            double out = edge + (outward > 0 ? 1.5 : -0.5);
            return onX ? new Location(world, out, floorY + up, mid + 0.5) : new Location(world, mid + 0.5, floorY + up, out);
        }

        /** A portcullis: iron bars over the opening, three wide and three high from {@code baseY}, along the edge. */
        public @NotNull Map<Location, BlockData> bars(@Nullable World world, int baseY) {
            Map<Location, BlockData> out = new HashMap<>();
            BlockData bars = Material.IRON_BARS.createBlockData();
            if (bars instanceof MultipleFacing facing) {
                facing.setFace(onX ? BlockFace.NORTH : BlockFace.EAST, true);
                facing.setFace(onX ? BlockFace.SOUTH : BlockFace.WEST, true);
            }
            for (int along = mid - 1; along <= mid + 1; along++) {
                for (int y = baseY; y < baseY + 3; y++) {
                    out.put(onX ? new Location(world, edge, y, along) : new Location(world, along, y, edge), bars);
                }
            }
            return out;
        }
    }

    /** The edge of an area that faces a point: across the axis it is further off on, north or south on a tie. */
    public static @NotNull Entrance facing(int minX, int minZ, int maxX, int maxZ, double x, double z) {
        double dx = (minX + maxX + 1) / 2.0 - x;
        double dz = (minZ + maxZ + 1) / 2.0 - z;
        if (Math.abs(dx) > Math.abs(dz)) {
            return new Entrance(dx > 0 ? minX : maxX, (minZ + maxZ) / 2, true, dx > 0 ? -1 : 1);
        }
        return new Entrance(dz > 0 ? minZ : maxZ, (minX + maxX) / 2, false, dz > 0 ? -1 : 1);
    }

    /**
     * The edge of an area that faces a road running the way {@code yaw} looks through {@code (x, z)}, or
     * {@code null} when the road runs through the area (no edge faces it).
     */
    public static @Nullable Entrance alongRoad(int minX, int minZ, int maxX, int maxZ, double x, double z, double yaw) {
        boolean alongZ = Math.abs(Math.cos(Math.toRadians(yaw))) >= Math.abs(Math.sin(Math.toRadians(yaw)));
        double line = alongZ ? x : z;
        int min = alongZ ? minX : minZ, max = alongZ ? maxX : maxZ;
        int mid = alongZ ? (minZ + maxZ) / 2 : (minX + maxX) / 2;
        if (line < min) return new Entrance(min, mid, alongZ, -1);
        if (line > max) return new Entrance(max, mid, alongZ, 1);
        return null;
    }

    /** A wall {@code height} blocks high from {@code baseY} along the border of a box. */
    public static @NotNull Map<Location, BlockData> border(@Nullable World world, int minX, int minZ, int maxX, int maxZ,
                                                            int baseY, int height, @NotNull BlockData block) {
        Map<Location, BlockData> out = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!onBorder(minX, minZ, maxX, maxZ, x, z)) continue;
                for (int y = baseY; y < baseY + height; y++) out.put(new Location(world, x, y, z), block);
            }
        }
        return out;
    }

    /** A fence of {@code material} at {@code y} along the border of a box, each post joined to its neighbours. */
    public static @NotNull Map<Location, BlockData> fence(@Nullable World world, int minX, int minZ, int maxX, int maxZ,
                                                           int y, @NotNull Material material) {
        Map<Location, BlockData> out = new HashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!onBorder(minX, minZ, maxX, maxZ, x, z)) continue;
                BlockData data = material.createBlockData();
                if (data instanceof MultipleFacing fence) {
                    fence.setFace(BlockFace.NORTH, z > minZ && (x == minX || x == maxX));
                    fence.setFace(BlockFace.SOUTH, z < maxZ && (x == minX || x == maxX));
                    fence.setFace(BlockFace.WEST, x > minX && (z == minZ || z == maxZ));
                    fence.setFace(BlockFace.EAST, x < maxX && (z == minZ || z == maxZ));
                }
                out.put(new Location(world, x, y, z), data);
            }
        }
        return out;
    }

    private static boolean onBorder(int minX, int minZ, int maxX, int maxZ, int x, int z) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }
}
