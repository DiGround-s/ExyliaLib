package net.exylia.lib.region;

/**
 * Finite, ordered horizontal bounds using minimum-inclusive, maximum-exclusive semantics.
 *
 * @param minX inclusive minimum x coordinate
 * @param maxX exclusive maximum x coordinate
 * @param minZ inclusive minimum z coordinate
 * @param maxZ exclusive maximum z coordinate
 * @since 1.23.0
 */
public record HorizontalBounds(double minX, double maxX, double minZ, double maxZ) {

    /** Validates finite, non-empty ordered bounds. */
    public HorizontalBounds {
        requireFinite(minX, "minX");
        requireFinite(maxX, "maxX");
        requireFinite(minZ, "minZ");
        requireFinite(maxZ, "maxZ");
        if (minX >= maxX || minZ >= maxZ) {
            throw new IllegalArgumentException("Horizontal bounds must be ordered and non-empty");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
