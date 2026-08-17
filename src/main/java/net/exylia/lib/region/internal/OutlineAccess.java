package net.exylia.lib.region.internal;

import net.exylia.lib.region.RegionShape;
import org.jetbrains.annotations.ApiStatus;

/**
 * Opens {@link OutlineSampler} to its tests.
 *
 * <p>The sampler stays package-private: nothing outside the region runtime
 * should be drawing outlines. Its arithmetic is worth testing directly, though.
 * The failure mode is a server drawing hundreds of thousands of particles for
 * one big region, and that does not show up until somebody looks at a claim on
 * a live server.
 */
@ApiStatus.Internal
public final class OutlineAccess {

    private OutlineAccess() {
    }

    /** How many points a shape's outline draws, worked out afresh. */
    public static int pointCount(RegionShape shape, double spacing) {
        return OutlineSampler.sampleUncached(shape, spacing).size();
    }

    /** The coordinates of a shape's outline, worked out afresh. */
    public static double[] points(RegionShape shape, double spacing) {
        return OutlineSampler.sampleUncached(shape, spacing).coordinates();
    }

    /** Whether y is supplied by the viewer rather than the shape. */
    public static boolean followsViewerHeight(RegionShape shape, double spacing) {
        return OutlineSampler.sampleUncached(shape, spacing).dynamicY();
    }

    /** The most points one frame may draw. */
    public static int maxPointsPerFrame() {
        return OutlineSampler.MAX_POINTS_PER_FRAME;
    }
}
