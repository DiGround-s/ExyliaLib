package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Immutable geometric boundary of a region.
 *
 * <p>Containment operates directly on primitive coordinates and creates no location objects.
 * Implementations use minimum-inclusive, maximum-exclusive coordinate boundaries where a shape
 * has explicit axis bounds.
 *
 * @since 1.23.0
 */
public sealed interface RegionShape
        permits Cuboid, UnboundedYRectangle, Sphere, HorizontalCylinder {

    /**
     * Tests whether a point is inside this shape.
     *
     * @param x point x coordinate
     * @param y point y coordinate
     * @param z point z coordinate
     * @return {@code true} when the point is contained
     */
    boolean contains(double x, double y, double z);

    /**
     * Returns finite horizontal bounds enclosing this shape.
     *
     * @return immutable horizontal bounds
     */
    @NotNull HorizontalBounds horizontalBounds();

    /**
     * Returns finite vertical bounds when this shape limits y.
     *
     * @return vertical bounds, or an empty optional for unbounded y
     */
    @NotNull Optional<VerticalBounds> verticalBounds();
}
