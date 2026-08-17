package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Finite sphere defined by its center and positive radius.
 *
 * <p>The curved surface is included. Its axis bounds are represented with an exclusive maximum
 * for indexing and persistence, while exact containment uses squared distance.
 *
 * @param centerX center x coordinate
 * @param centerY center y coordinate
 * @param centerZ center z coordinate
 * @param radius positive radius
 * @since 1.23.0
 */
public record Sphere(double centerX, double centerY, double centerZ, double radius)
        implements RegionShape {

    /** Validates finite center coordinates and radius. */
    public Sphere {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY)
                || !Double.isFinite(centerZ) || !Double.isFinite(radius)) {
            throw new IllegalArgumentException("Sphere coordinates and radius must be finite");
        }
        if (radius <= 0.0) {
            throw new IllegalArgumentException("Sphere radius must be positive");
        }
        if (!Double.isFinite(centerX - radius) || !Double.isFinite(centerX + radius)
                || !Double.isFinite(centerY - radius) || !Double.isFinite(centerY + radius)
                || !Double.isFinite(centerZ - radius) || !Double.isFinite(centerZ + radius)) {
            throw new IllegalArgumentException("Sphere bounds must be finite");
        }
    }

    @Override
    public boolean contains(double x, double y, double z) {
        double deltaX = x - centerX;
        double deltaY = y - centerY;
        double deltaZ = z - centerZ;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= radius * radius;
    }

    @Override
    public @NotNull HorizontalBounds horizontalBounds() {
        return new HorizontalBounds(centerX - radius, centerX + radius,
                centerZ - radius, centerZ + radius);
    }

    @Override
    public @NotNull Optional<VerticalBounds> verticalBounds() {
        return Optional.of(new VerticalBounds(centerY - radius, centerY + radius));
    }
}
