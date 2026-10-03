package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Finite upright cylinder: a circle on the horizontal plane between two heights.
 *
 * <p>{@link HorizontalCylinder} reaches from bedrock to the build limit; this one stops at
 * {@code minY} and {@code maxY}, which is what an area that is a round room, a well or a round
 * mine wants. The curved surface is included, the vertical bounds use an exclusive maximum like
 * every other shape.
 *
 * @param centerX center x coordinate
 * @param centerZ center z coordinate
 * @param radius  positive radius
 * @param minY    lowest y, inclusive
 * @param maxY    highest y, exclusive
 * @since 1.225.0
 */
public record Cylinder(double centerX, double centerZ, double radius, double minY, double maxY)
        implements RegionShape {

    /** Validates finite values, a positive radius and a positive height. */
    public Cylinder {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(radius)
                || !Double.isFinite(minY) || !Double.isFinite(maxY)) {
            throw new IllegalArgumentException("Cylinder coordinates, radius and heights must be finite");
        }
        if (radius <= 0.0) {
            throw new IllegalArgumentException("Cylinder radius must be positive");
        }
        if (maxY <= minY) {
            throw new IllegalArgumentException("Cylinder maxY must be above minY");
        }
        if (!Double.isFinite(centerX - radius) || !Double.isFinite(centerX + radius)
                || !Double.isFinite(centerZ - radius) || !Double.isFinite(centerZ + radius)) {
            throw new IllegalArgumentException("Cylinder bounds must be finite");
        }
    }

    @Override
    public boolean contains(double x, double y, double z) {
        if (y < minY || y >= maxY) return false;
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
        return Optional.of(new VerticalBounds(minY, maxY));
    }
}
