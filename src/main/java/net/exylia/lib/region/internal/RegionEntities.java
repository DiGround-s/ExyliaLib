package net.exylia.lib.region.internal;

import net.exylia.lib.region.HorizontalBounds;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.VerticalBounds;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.BoundingBox;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Removing what a region has collected.
 *
 * <p>Split from {@code PluginRegions} because the two halves are testable on
 * different terms: which box to sweep is arithmetic, and sweeping it is a world
 * read that needs a server.
 */
public final class RegionEntities {

    private RegionEntities() {
        throw new AssertionError("No instances.");
    }

    /**
     * The default of what counts as loose: what a match, a mine or an arena
     * leaves behind, and nothing anybody placed.
     *
     * <p>Deliberately does not include armour stands, item frames, paintings or
     * mobs. A decorated region cleared between rounds would lose its decoration
     * once and never say so, and that is not a mistake a caller can undo.
     */
    public static boolean loose(Entity entity) {
        return entity instanceof Item
                || entity instanceof ExperienceOrb
                || entity instanceof Projectile
                || entity instanceof Minecart
                || entity instanceof EnderCrystal
                || entity instanceof Firework;
    }

    /**
     * The box that encloses a shape, in world coordinates.
     *
     * <p>A shape without vertical bounds takes the world's, since that is what
     * "unbounded y" means to a sweep that has to name numbers. Nothing is
     * padded: a shape's maxima are already exclusive world coordinates, so the
     * region of blocks {@code 0..15} reports {@code 16.0} and an entity resting
     * on the last block is inside the box as written.
     *
     * @param shape     the shape to enclose
     * @param minHeight the world's floor, used when the shape has no vertical bounds
     * @param maxHeight the world's ceiling, used when the shape has no vertical bounds
     * @return the enclosing box
     */
    public static BoundingBox box(RegionShape shape, int minHeight, int maxHeight) {
        HorizontalBounds horizontal = shape.horizontalBounds();
        Optional<VerticalBounds> vertical = shape.verticalBounds();
        double minY = vertical.map(VerticalBounds::minY).orElse((double) minHeight);
        double maxY = vertical.map(VerticalBounds::maxY).orElse((double) maxHeight);
        return new BoundingBox(
                horizontal.minX(), minY, horizontal.minZ(),
                horizontal.maxX(), maxY, horizontal.maxZ());
    }

    /**
     * Removes the entities a shape contains that a caller asks for.
     *
     * <p>The box only narrows the world read; membership is the shape's own
     * {@code contains}, so a sphere does not clear the corners of the cube
     * around it. Players are never removed, whatever the predicate says: a
     * region is cleared of what the last round dropped, not of who is standing
     * in it.
     *
     * @param world the world to sweep
     * @param shape the region's shape
     * @param which what to remove among the entities inside
     * @return how many were removed
     */
    public static int clear(World world, RegionShape shape, Predicate<Entity> which) {
        int removed = 0;
        for (Entity entity : world.getNearbyEntities(
                box(shape, world.getMinHeight(), world.getMaxHeight()))) {
            if (entity instanceof Player) {
                continue;
            }
            Location at = entity.getLocation();
            if (!shape.contains(at.getX(), at.getY(), at.getZ())) {
                continue;
            }
            if (!which.test(entity)) {
                continue;
            }
            entity.remove();
            removed++;
        }
        return removed;
    }
}
