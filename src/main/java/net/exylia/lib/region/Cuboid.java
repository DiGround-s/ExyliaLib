package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Axis-aligned finite cuboid with minimum-inclusive, maximum-exclusive containment.
 *
 * @param minX first x boundary; constructor input order is normalized
 * @param minY first y boundary; constructor input order is normalized
 * @param minZ first z boundary; constructor input order is normalized
 * @param maxX second x boundary; constructor input order is normalized
 * @param maxY second y boundary; constructor input order is normalized
 * @param maxZ second z boundary; constructor input order is normalized
 * @since 1.23.0
 */
public record Cuboid(double minX, double minY, double minZ,
                     double maxX, double maxY, double maxZ) implements RegionShape {

    /** Normalizes and validates finite, non-empty bounds. */
    public Cuboid {
        requireFinite(minX, "minX");
        requireFinite(minY, "minY");
        requireFinite(minZ, "minZ");
        requireFinite(maxX, "maxX");
        requireFinite(maxY, "maxY");
        requireFinite(maxZ, "maxZ");

        double firstX = minX;
        double firstY = minY;
        double firstZ = minZ;
        minX = Math.min(firstX, maxX);
        minY = Math.min(firstY, maxY);
        minZ = Math.min(firstZ, maxZ);
        maxX = Math.max(firstX, maxX);
        maxY = Math.max(firstY, maxY);
        maxZ = Math.max(firstZ, maxZ);
        if (minX == maxX || minY == maxY || minZ == maxZ) {
            throw new IllegalArgumentException("Cuboid bounds must enclose a non-zero volume");
        }
    }

    /**
     * Creates a cuboid from inclusive block coordinates.
     *
     * <p>The maximum block on each axis is converted to its exclusive coordinate boundary by
     * adding one, preserving the inclusive block behavior of the Commons region implementation.
     *
     * @param first one inclusive block corner
     * @param second the other inclusive block corner in the same world
     * @return a cuboid containing every block between the corners
     */
    public static @NotNull Cuboid blocks(@NotNull BlockPosition first,
                                         @NotNull BlockPosition second) {
        if (!first.world().equals(second.world())) {
            throw new IllegalArgumentException("Cuboid block corners must be in the same world");
        }
        return blocks(first.x(), first.y(), first.z(), second.x(), second.y(), second.z());
    }

    /**
     * Creates a cuboid from inclusive integer block coordinates.
     *
     * @return a cuboid containing every block between the supplied corners
     */
    public static @NotNull Cuboid blocks(int firstX, int firstY, int firstZ,
                                         int secondX, int secondY, int secondZ) {
        return new Cuboid(Math.min(firstX, secondX), Math.min(firstY, secondY),
                Math.min(firstZ, secondZ), exclusiveMaximum(firstX, secondX),
                exclusiveMaximum(firstY, secondY), exclusiveMaximum(firstZ, secondZ));
    }

    /** Creates a cuboid containing exactly one block. */
    public static @NotNull Cuboid block(int x, int y, int z) {
        return new Cuboid(x, y, z, (double) x + 1.0, (double) y + 1.0, (double) z + 1.0);
    }

    /** Creates a cuboid containing exactly the supplied block. */
    public static @NotNull Cuboid block(@NotNull BlockPosition position) {
        return block(position.x(), position.y(), position.z());
    }

    @Override
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }

    @Override
    public @NotNull HorizontalBounds horizontalBounds() {
        return new HorizontalBounds(minX, maxX, minZ, maxZ);
    }

    @Override
    public @NotNull Optional<VerticalBounds> verticalBounds() {
        return Optional.of(new VerticalBounds(minY, maxY));
    }

    private static double exclusiveMaximum(int first, int second) {
        return (double) Math.max(first, second) + 1.0;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
