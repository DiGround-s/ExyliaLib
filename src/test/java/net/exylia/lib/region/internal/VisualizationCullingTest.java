package net.exylia.lib.region.internal;

import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.UnboundedYRectangle;
import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deciding whether a viewer is close enough to be drawn to.
 *
 * <p>The check is against the region's bounds rather than its centre, which is
 * the whole point: an arena a hundred blocks wide has a centre nobody stands
 * at, and measuring to it would stop drawing the border to the players standing
 * on it.
 *
 * <p>{@link Location} is constructed without a world on purpose — it is a
 * coordinate holder, and this decision reads nothing else.
 */
class VisualizationCullingTest {

    /** Sixteen blocks square, sixteen tall, at the origin. */
    private static final Cuboid ARENA = Cuboid.blocks(0, 64, 0, 15, 79, 15);

    @Test
    @DisplayName("standing inside is no distance at all")
    void inside() {
        assertEquals(0.0, VisualizationRuntime.squaredGap(ARENA, at(8, 70, 8)));
    }

    @Test
    @DisplayName("standing on the far edge of a wide region is standing at it")
    void farEdgeIsNear() {
        // The centre is eight blocks away, but the edge is under their feet.
        // A centre-based check with a small reach would stop drawing here.
        assertEquals(0.0, VisualizationRuntime.squaredGap(ARENA, at(16, 70, 16)));
    }

    @Test
    @DisplayName("outside is measured to the nearest corner, not the middle")
    void outside() {
        // Ten blocks past the maximum on both horizontal axes.
        assertEquals(200.0, VisualizationRuntime.squaredGap(ARENA, at(26, 70, 26)));
        // And below it, on the one axis that is out.
        assertEquals(400.0, VisualizationRuntime.squaredGap(ARENA, at(8, 44, 8)));
    }

    @Test
    @DisplayName("a shape with no ceiling is measured flat")
    void unboundedHeightIsIgnored() {
        // Its outline is drawn at the viewer's own height, so a player in the
        // sky above a claim is directly above it, not two hundred blocks away.
        UnboundedYRectangle claim = new UnboundedYRectangle(0, 0, 16, 16);

        assertEquals(0.0, VisualizationRuntime.squaredGap(claim, at(8, 300, 8)));
    }

    @Test
    @DisplayName("the far side of the world is out of reach")
    void acrossTheMap() {
        double reach = 48.0;

        assertTrue(VisualizationRuntime.squaredGap(ARENA, at(2000, 70, 2000)) > reach * reach);
    }

    private static Location at(double x, double y, double z) {
        return new Location(null, x, y, z);
    }
}
