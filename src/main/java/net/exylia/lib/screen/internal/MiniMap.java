package net.exylia.lib.screen.internal;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ground around a player as a small picture, turned so the way they face is up, drawn with the vanilla font like
 * any {@code <img>} (1.585.0): {@code <img map="32">}. No map item and no resource pack: each pixel is the map colour
 * of the top block of one column, shaded by the height step as a vanilla map shades it.
 *
 * <p>Reads only chunks that are loaded and owned by the thread asking (Folia regions), so it never loads a chunk or
 * reaches across a region; anything else is left dark. A player standing still is sampled again every two seconds.
 *
 * <p>Internal: free to change without notice.
 */
@ApiStatus.Internal
public final class MiniMap {

    /** The sides a map takes, in pixels. */
    public static final int MIN = 8, MAX = 64;

    private static final int UNKNOWN = 0xC0120E1A;
    private static final int MARKER = 0xFFFFFFFF;
    private static final int MARKER_EDGE = 0xFF120E1A;

    private record Key(String world, int x, int z, int heading, int size, int zoom, boolean round, int ring) {
    }

    private record Drawn(Key key, int[] argb, long at) {
    }

    // ponytail: one entry per player who ever showed a map, dropped on quit by the size cap; a listener if it grows.
    private static final Map<UUID, Drawn> LAST = new ConcurrentHashMap<>();

    private MiniMap() {
    }

    /**
     * {@code size} by {@code size} colours around {@code player}, {@code zoom} blocks a pixel, a white arrow in the
     * middle; {@code round} cuts it to a disc edged by {@code ring} (ARGB, {@code 0} none).
     */
    public static int[] of(Player player, int size, int zoom, boolean round, int ring) {
        size = Math.max(MIN, Math.min(MAX, size));
        zoom = Math.max(1, Math.min(8, zoom));
        var at = player.getLocation();
        // Turned in steps of 6 degrees, so a slow turn does not resample every read.
        int heading = Math.floorMod(Math.round(at.getYaw() / 6f), 60);
        Key key = new Key(at.getWorld().getName(), at.getBlockX(), at.getBlockZ(), heading, size, zoom, round, ring);
        Drawn last = LAST.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        // Blocks change under a player standing still too: sampled again at most every two seconds.
        if (last != null && last.key.equals(key) && now - last.at < 2000) return last.argb;
        int[] argb = sample(player.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), heading * 6, size, zoom);
        if (round) disc(argb, size, ring);
        marker(argb, size);
        if (LAST.size() > 4096) LAST.clear();
        LAST.put(player.getUniqueId(), new Drawn(key, argb, now));
        return argb;
    }

    private static int[] sample(World world, int px, int py, int pz, double yawDegrees, int size, int zoom) {
        double yaw = Math.toRadians(yawDegrees);
        // Facing (yaw 0 is south, +Z) and its right hand side, in world x and z.
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -fz, rz = fx;
        boolean ceiling = world.hasCeiling();
        int[] heights = new int[size * size];
        int[] base = new int[size * size];
        double half = (size - 1) / 2.0;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                double right = (col - half) * zoom, ahead = (half - row) * zoom;
                int x = (int) Math.floor(px + 0.5 + right * rx + ahead * fx);
                int z = (int) Math.floor(pz + 0.5 + right * rz + ahead * fz);
                int i = row * size + col;
                int cx = x >> 4, cz = z >> 4;
                if (!world.isChunkLoaded(cx, cz) || !Bukkit.isOwnedByCurrentRegion(world, cx, cz)) {
                    base[i] = UNKNOWN;
                    heights[i] = Integer.MIN_VALUE;
                    continue;
                }
                Block top = ceiling ? under(world, x, py + 2, z) : world.getHighestBlockAt(x, z, HeightMap.WORLD_SURFACE);
                if (top == null) {
                    base[i] = UNKNOWN;
                    heights[i] = Integer.MIN_VALUE;
                    continue;
                }
                Color c = top.getBlockData().getMapColor();
                base[i] = 0xFF000000 | c.asRGB();
                heights[i] = top.getY();
            }
        }
        int[] out = new int[size * size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int i = row * size + col;
                if (base[i] == UNKNOWN) {
                    out[i] = UNKNOWN;
                    continue;
                }
                // Shaded against the pixel above it on screen (the way the player faces), as a map shades by north.
                int above = row > 0 ? heights[i - size] : Integer.MIN_VALUE;
                double shade = above == Integer.MIN_VALUE || heights[i] == above ? 0.86 : heights[i] > above ? 1.0 : 0.71;
                out[i] = shade(base[i], shade);
            }
        }
        return out;
    }

    /** In a world with a roof (the Nether), the first solid block at or under the player's head. */
    private static Block under(World world, int x, int fromY, int z) {
        int floor = Math.max(world.getMinHeight(), fromY - 24);
        for (int y = Math.min(fromY, world.getMaxHeight() - 1); y >= floor; y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (!type.isAir()) return block;
        }
        return null;
    }

    static int shade(int argb, double by) {
        int r = (int) (((argb >> 16) & 0xFF) * by), g = (int) (((argb >> 8) & 0xFF) * by), b = (int) ((argb & 0xFF) * by);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** Clears what lies outside the disc and draws its edge. */
    static void disc(int[] argb, int size, int ring) {
        double c = (size - 1) / 2.0, outer = size / 2.0, edge = outer - 1;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                double d = Math.hypot(col - c, row - c);
                int i = row * size + col;
                if (d > outer - 0.25) argb[i] = 0;
                else if (ring != 0 && d > edge - 0.25) argb[i] = ring;
            }
        }
    }

    /** A small solid triangle in the middle pointing up, edged dark so it reads on snow and on grass. */
    static void marker(int[] argb, int size) {
        int c = size / 2;
        int[][] arrow = {{0, -1}, {-1, 0}, {0, 0}, {1, 0}, {-2, 1}, {-1, 1}, {0, 1}, {1, 1}, {2, 1}};
        for (int[] p : arrow) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) set(argb, size, c + p[0] + dx, c + p[1] + dy, MARKER_EDGE, true);
            }
        }
        for (int[] p : arrow) set(argb, size, c + p[0], c + p[1], MARKER, false);
    }

    private static void set(int[] argb, int size, int x, int y, int colour, boolean onlyGround) {
        if (x < 0 || y < 0 || x >= size || y >= size) return;
        int i = y * size + x;
        if (onlyGround && (argb[i] == MARKER || argb[i] == 0)) return;
        argb[i] = colour;
    }
}
