package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Axis-aligned horizontal rectangle that contains every y coordinate.
 *
 * @param minX first x boundary; constructor input order is normalized
 * @param minZ first z boundary; constructor input order is normalized
 * @param maxX second x boundary; constructor input order is normalized
 * @param maxZ second z boundary; constructor input order is normalized
 * @since 1.23.0
 */
public record UnboundedYRectangle(double minX, double minZ, double maxX, double maxZ)
        implements RegionShape {

    /** Normalizes and validates finite, non-empty horizontal bounds. */
    public UnboundedYRectangle {
        requireFinite(minX, "minX");
        requireFinite(minZ, "minZ");
        requireFinite(maxX, "maxX");
        requireFinite(maxZ, "maxZ");
        double firstX = minX;
        double firstZ = minZ;
        minX = Math.min(firstX, maxX);
        minZ = Math.min(firstZ, maxZ);
        maxX = Math.max(firstX, maxX);
        maxZ = Math.max(firstZ, maxZ);
        if (minX == maxX || minZ == maxZ) {
            throw new IllegalArgumentException("Rectangle bounds must enclose a non-zero area");
        }
    }

    @Override
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }

    @Override
    public @NotNull HorizontalBounds horizontalBounds() {
        return new HorizontalBounds(minX, maxX, minZ, maxZ);
    }

    @Override
    public @NotNull Optional<VerticalBounds> verticalBounds() {
        return Optional.empty();
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
