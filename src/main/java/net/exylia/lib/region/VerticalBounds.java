package net.exylia.lib.region;

/**
 * Finite, ordered vertical bounds using minimum-inclusive, maximum-exclusive semantics.
 *
 * @param minY inclusive minimum y coordinate
 * @param maxY exclusive maximum y coordinate
 * @since 1.23.0
 */
public record VerticalBounds(double minY, double maxY) {

    /** Validates finite, non-empty ordered bounds. */
    public VerticalBounds {
        if (!Double.isFinite(minY) || !Double.isFinite(maxY)) {
            throw new IllegalArgumentException("Vertical bounds must be finite");
        }
        if (minY >= maxY) {
            throw new IllegalArgumentException("Vertical bounds must be ordered and non-empty");
        }
    }
}
