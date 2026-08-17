package net.exylia.lib.region;

import net.exylia.lib.region.internal.OutlineAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The points that draw a region's outline.
 *
 * <p>Worth testing on its own because the cost is paid per point, per viewer,
 * per frame, and a mistake here is invisible until somebody looks at a large
 * claim on a live server and the server stops.
 */
class OutlineSamplerTest {

    /** A claim as wide as the ones ExyliaClans really stores. */
    private static final Cuboid HUGE = new Cuboid(-50_000, 0, -50_000, 50_000, 384, 50_000);

    @Test
    @DisplayName("a region a hundred thousand blocks wide costs no more than a small one")
    void hugeRegionsCostTheSame() {
        // The budget is spent before the points are made. Generating the whole
        // outline and thinning it afterwards would produce eight hundred
        // thousand points to keep five hundred, which is the difference between
        // drawing an outline and stalling the server.
        int small = OutlineAccess.pointCount(new Cuboid(0, 0, 0, 10, 10, 10), 1.0);
        int huge = OutlineAccess.pointCount(HUGE, 1.0);

        assertTrue(small <= OutlineAccess.maxPointsPerFrame(), "small: " + small);
        assertTrue(huge <= OutlineAccess.maxPointsPerFrame(), "huge: " + huge);
    }

    @Test
    @DisplayName("no shape ever draws more than one frame's budget")
    void everyShapeStaysWithinBudget() {
        List<RegionShape> shapes = List.of(
                new Cuboid(0, 0, 0, 1, 1, 1),
                HUGE,
                new UnboundedYRectangle(-40_000, -40_000, 40_000, 40_000),
                new Sphere(0, 64, 0, 5_000),
                new HorizontalCylinder(0, 0, 20_000));

        for (RegionShape shape : shapes) {
            for (double spacing : new double[] {0.05, 0.5, 1.0, 4.0}) {
                int points = OutlineAccess.pointCount(shape, spacing);
                assertTrue(points <= OutlineAccess.maxPointsPerFrame(),
                        shape.getClass().getSimpleName() + " at spacing " + spacing
                                + " drew " + points);
                assertTrue(points > 0, shape + " drew nothing");
            }
        }
    }

    @Test
    @DisplayName("a cuboid draws each of its eight corners exactly once")
    void cornersAreNotDrawnThreeTimes() {
        // Three edges meet at a corner. Letting each draw its endpoints would
        // put three particles in the same place, which is both wasteful and
        // visibly brighter at the corners.
        double[] points = OutlineAccess.points(new Cuboid(0, 0, 0, 10, 10, 10), 1.0);

        Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < points.length; offset += 3) {
            String key = points[offset] + "," + points[offset + 1] + "," + points[offset + 2];
            assertTrue(seen.add(key), "point drawn twice: " + key);
        }
    }

    @Test
    @DisplayName("a cuboid outline reaches all eight corners")
    void allCornersAreDrawn() {
        double[] points = OutlineAccess.points(new Cuboid(0, 0, 0, 10, 20, 30), 1.0);

        Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < points.length; offset += 3) {
            seen.add(points[offset] + "," + points[offset + 1] + "," + points[offset + 2]);
        }
        for (double x : new double[] {0, 10}) {
            for (double y : new double[] {0, 20}) {
                for (double z : new double[] {0, 30}) {
                    assertTrue(seen.contains(x + "," + y + "," + z),
                            "corner missing: " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    @DisplayName("every one of the twelve edges is drawn, not just the first few")
    void everyEdgeGetsItsShare() {
        // The budget is shared out per edge. Letting the first edge take
        // whatever it wants would draw one complete edge and eleven bare ones,
        // which still fits the budget and still reaches every corner — so only
        // looking at those two things would miss it entirely.
        double[] points = OutlineAccess.points(HUGE, 1.0);

        int[] perEdge = new int[12];
        for (int offset = 0; offset < points.length; offset += 3) {
            int edge = edgeOf(points[offset], points[offset + 1], points[offset + 2]);
            if (edge >= 0) {
                perEdge[edge]++;
            }
        }
        for (int edge = 0; edge < perEdge.length; edge++) {
            assertTrue(perEdge[edge] > 0, "edge " + edge + " was left bare");
        }
    }

    /**
     * Which of a cuboid's twelve edges a point lies on, or -1 for a corner.
     *
     * <p>A point on an edge is at a minimum or maximum on exactly two axes and
     * somewhere in between on the third; a corner is at an extreme on all three.
     */
    private static int edgeOf(double x, double y, double z) {
        boolean fixedX = x == -50_000 || x == 50_000;
        boolean fixedY = y == 0 || y == 384;
        boolean fixedZ = z == -50_000 || z == 50_000;
        int index = (x == 50_000 ? 1 : 0) + (y == 384 ? 2 : 0) + (z == 50_000 ? 4 : 0);
        if (fixedX && fixedY && !fixedZ) {
            return (index & 3);
        }
        if (fixedX && fixedZ && !fixedY) {
            return 4 + ((index & 1) | ((index & 4) >> 1));
        }
        if (fixedY && fixedZ && !fixedX) {
            return 8 + (((index & 2) >> 1) | ((index & 4) >> 1));
        }
        return -1;
    }

    @Test
    @DisplayName("the shapes with no ceiling are drawn at the viewer's height")
    void unboundedShapesFollowTheViewer() {
        // A rectangle with no vertical limit drawn at y=0 would be underground
        // and invisible. Its own y means nothing, so the viewer supplies it.
        assertTrue(OutlineAccess.followsViewerHeight(
                new UnboundedYRectangle(0, 0, 10, 10), 1.0), "rectangle");
        assertTrue(OutlineAccess.followsViewerHeight(
                new HorizontalCylinder(0, 0, 10), 1.0), "cylinder");

        // A cuboid and a sphere know where they are vertically.
        assertFalse(OutlineAccess.followsViewerHeight(
                new Cuboid(0, 0, 0, 10, 10, 10), 1.0), "cuboid");
        assertFalse(OutlineAccess.followsViewerHeight(
                new Sphere(0, 64, 0, 10), 1.0), "sphere");
    }

    @Test
    @DisplayName("a sphere is three rings, not a filled ball")
    void sphereIsDrawnAsCircles() {
        // Every point sits on the surface, and every one lies on one of the
        // three great circles. Drawing the surface itself would be thousands of
        // particles for a shape three rings already describe.
        double radius = 8.0;
        double[] points = OutlineAccess.points(new Sphere(0, 0, 0, radius), 1.0);

        for (int offset = 0; offset < points.length; offset += 3) {
            double x = points[offset];
            double y = points[offset + 1];
            double z = points[offset + 2];
            double distance = Math.sqrt(x * x + y * y + z * z);
            assertEquals(radius, distance, 1e-9, "point is not on the surface");

            boolean onACircle = Math.abs(y) < 1e-9 || Math.abs(z) < 1e-9 || Math.abs(x) < 1e-9;
            assertTrue(onACircle, "point is not on one of the three rings");
        }
    }

    @Test
    @DisplayName("a circle stays a circle even when the budget is tight")
    void tinyBudgetStillLooksRound() {
        // Cheaper to draw is fine; a triangle is not.
        int points = OutlineAccess.pointCount(new HorizontalCylinder(0, 0, 200_000), 1.0);
        assertTrue(points >= 8, "a ring needs enough points to read as one: " + points);
    }

    @Test
    @DisplayName("a bigger ring is drawn with more points, so it stays smooth")
    void biggerRingsUseMorePoints() {
        // A fixed count would make a small sphere lumpy or a large one a
        // polygon; the number of points follows the circumference until the
        // budget stops it.
        int small = OutlineAccess.pointCount(new Sphere(0, 0, 0, 2), 1.0);
        int large = OutlineAccess.pointCount(new Sphere(0, 0, 0, 40), 1.0);

        assertTrue(large > small, "small=" + small + " large=" + large);
    }

    @Test
    @DisplayName("the same shape and spacing always draw the same points")
    void samplingIsDeterministic() {
        // The result is cached, so it must not depend on anything else.
        double[] first = OutlineAccess.points(HUGE, 1.0);
        double[] second = OutlineAccess.points(HUGE, 1.0);

        assertEquals(first.length, second.length);
        for (int index = 0; index < first.length; index++) {
            assertEquals(first[index], second[index], 0.0);
        }
    }

    @Test
    @DisplayName("a finer spacing draws more of a shape, up to the budget")
    void spacingChangesDetail() {
        Cuboid small = new Cuboid(0, 0, 0, 20, 20, 20);

        assertTrue(OutlineAccess.pointCount(small, 0.5)
                        > OutlineAccess.pointCount(small, 4.0),
                "asking for closer points should draw more of them");
    }
}
