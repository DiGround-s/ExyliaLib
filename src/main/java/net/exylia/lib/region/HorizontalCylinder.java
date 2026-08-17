package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Circular horizontal region that ignores y and therefore extends vertically without limit.
 *
 * @param centerX center x coordinate
 * @param centerZ center z coordinate
 * @param radius positive radius
 * @since 1.23.0
 */
public record HorizontalCylinder(double centerX, double centerZ, double radius)
        implements RegionShape {

    /** Validates finite center coordinates and radius. */
    public HorizontalCylinder {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(radius)) {
            throw new IllegalArgumentException("Cylinder coordinates and radius must be finite");
        }
        if (radius <= 0.0) {
            throw new IllegalArgumentException("Cylinder radius must be positive");
        }
        if (!Double.isFinite(centerX - radius) || !Double.isFinite(centerX + radius)
                || !Double.isFinite(centerZ - radius) || !Double.isFinite(centerZ + radius)) {
            throw new IllegalArgumentException("Cylinder bounds must be finite");
        }
    }

    @Override
    public boolean contains(double x, double y, double z) {
        double deltaX = x - centerX;
        double deltaZ = z - centerZ;
        return deltaX * deltaX + deltaZ * deltaZ <= radius * radius;
    }

    @Override
    public @NotNull HorizontalBounds horizontalBounds() {
        return new HorizontalBounds(centerX - radius, centerX + radius,
                centerZ - radius, centerZ + radius);
    }

    @Override
    public @NotNull Optional<VerticalBounds> verticalBounds() {
        return Optional.empty();
    }
}
