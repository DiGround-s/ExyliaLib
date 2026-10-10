package net.exylia.lib.region;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A registered region's box, the way block loops and "who is in here" want it.
 *
 * <pre>{@code
 * Location min = Boxes.min(mine), max = Boxes.max(mine);
 * for (int x = min.getBlockX(); x <= max.getBlockX(); x++) { ... }
 *
 * if (Boxes.loaded(mine)) refill(mine);
 * Boxes.playersInside(mine).forEach(player -> player.teleport(spawn));
 * }</pre>
 *
 * <p>A {@link RegionShape} speaks in bounds and a world id. Filling a mine,
 * counting its volume or pasting into it wants two corners in a Bukkit world,
 * so the conversion lives here once.
 *
 * <h2>The maxima are inclusive</h2>
 * A shape's maxima are exclusive, which is what makes a single block's own
 * volume come out right; a {@code for} loop over blocks wants the last block,
 * not the first one past it. So {@link #max} is one block back.
 *
 * <p>A shape with no height limit takes the world's build height, or 0 to 256
 * when the world is not loaded here.
 *
 * <p>Everything here reads the live world, so it runs on the thread that owns
 * the region's area.
 *
 * @since 1.266.0
 */
public final class Boxes {

    private Boxes() {
        throw new AssertionError("No instances.");
    }

    /** The world the region is in, or {@code null} when it is not loaded on this server. */
    public static @Nullable World world(@NotNull RegionSnapshot region) {
        return Bukkit.getWorld(region.worldId());
    }

    /** The lower corner, inclusive. */
    public static @NotNull Location min(@NotNull RegionSnapshot region) {
        World world = world(region);
        HorizontalBounds bounds = region.shape().horizontalBounds();
        double y = region.shape().verticalBounds().map(VerticalBounds::minY)
                .orElse(world == null ? 0.0 : world.getMinHeight());
        return new Location(world, bounds.minX(), y, bounds.minZ());
    }

    /** The upper corner, inclusive: one block back from the shape's exclusive maximum. */
    public static @NotNull Location max(@NotNull RegionSnapshot region) {
        World world = world(region);
        HorizontalBounds bounds = region.shape().horizontalBounds();
        double y = region.shape().verticalBounds().map(VerticalBounds::maxY)
                .orElse(world == null ? 256.0 : world.getMaxHeight());
        return new Location(world, bounds.maxX() - 1, y - 1, bounds.maxZ() - 1);
    }

    /** Whether a location is inside the shape itself, not merely its box. */
    public static boolean contains(@NotNull RegionSnapshot region, @Nullable Location where) {
        return where != null
                && where.getWorld() != null
                && where.getWorld().getUID().equals(region.worldId())
                && region.shape().contains(where.getX(), where.getY(), where.getZ());
    }

    /**
     * How many blocks the region's box holds.
     *
     * <p>The box rather than the shape: it is what a fill, or a "how much of
     * this is air" count, walks.
     */
    public static long volume(@NotNull RegionSnapshot region) {
        Location min = min(region);
        Location max = max(region);
        return (long) (max.getBlockX() - min.getBlockX() + 1)
                * (max.getBlockY() - min.getBlockY() + 1)
                * (max.getBlockZ() - min.getBlockZ() + 1);
    }

    /**
     * Whether every chunk the box touches is loaded, asked without loading any.
     *
     * <p>For timed work that walks the box — a refill, a paste — and would
     * otherwise load every chunk synchronously for an area nobody is near.
     * {@code false} when the world is not loaded here.
     */
    public static boolean loaded(@NotNull RegionSnapshot region) {
        World world = world(region);
        if (world == null) return false;
        Location min = min(region);
        Location max = max(region);
        for (int chunkX = min.getBlockX() >> 4; chunkX <= max.getBlockX() >> 4; chunkX++) {
            for (int chunkZ = min.getBlockZ() >> 4; chunkZ <= max.getBlockZ() >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    /**
     * Everyone standing in the region right now.
     *
     * <p>Regions are indexed by position, not by occupant, so this walks the
     * world's players: paid only when somebody asks.
     */
    public static @NotNull List<Player> playersInside(@NotNull RegionSnapshot region) {
        World world = world(region);
        if (world == null) return List.of();
        List<Player> inside = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (contains(region, player.getLocation())) inside.add(player);
        }
        return inside;
    }
}
