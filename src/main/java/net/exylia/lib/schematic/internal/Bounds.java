package net.exylia.lib.schematic.internal;

import net.exylia.lib.region.Cuboid;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * An inclusive integer block box.
 *
 * <p>The module's own shape, rather than {@link Cuboid} carried everywhere: a
 * schematic is made of blocks, and a box whose maximum is exclusive and
 * fractional would have to be rounded at every single use — once in the engine,
 * once when clearing entities, once when rescuing a player. Rounding it once,
 * here, is what makes those three agree on which blocks are inside.
 */
@ApiStatus.Internal
public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /**
     * Rounds a cuboid down to the blocks it encloses.
     *
     * <p>{@link Cuboid} is maximum-exclusive, so a box from 0 to 5 holds the
     * blocks 0 through 4.
     *
     * @param cuboid the region shape
     * @return the block box it covers
     */
    public static @NotNull Bounds of(@NotNull Cuboid cuboid) {
        return new Bounds(
                (int) Math.floor(cuboid.minX()),
                (int) Math.floor(cuboid.minY()),
                (int) Math.floor(cuboid.minZ()),
                (int) Math.ceil(cuboid.maxX()) - 1,
                (int) Math.ceil(cuboid.maxY()) - 1,
                (int) Math.ceil(cuboid.maxZ()) - 1);
    }

    /**
     * Returns whether a block is inside, maximum included.
     *
     * @param x block x
     * @param y block y
     * @param z block z
     * @return {@code true} when the block is part of this box
     */
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** The x of the middle block, for scheduling work at the box. */
    public int centreX() {
        return minX + (maxX - minX) / 2;
    }

    /** The y of the middle block. */
    public int centreY() {
        return minY + (maxY - minY) / 2;
    }

    /** The z of the middle block. */
    public int centreZ() {
        return minZ + (maxZ - minZ) / 2;
    }
}
