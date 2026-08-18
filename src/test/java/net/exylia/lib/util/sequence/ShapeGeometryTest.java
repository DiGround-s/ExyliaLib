package net.exylia.lib.util.sequence;

import net.exylia.lib.util.sequence.internal.ShapePoints;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The geometry of the built-in shapes.
 *
 * <p>Asserted as numbers rather than eyeballed on a server: a shape that drifts
 * half a block is a visible bug and an invisible code change, and nobody
 * notices it in a diff.
 */
class ShapeGeometryTest {

    private static final double EPSILON = 1.0e-9;

    // ------------------------------------------------------------------ counts

    @Test
    @DisplayName("a circle draws the points it was asked for, at the radius it was given")
    void circleIsRoundAndTheRightSize() {
        List<Vector> points = ShapePoints.of("circle", "radius:2.0;points:24");

        assertEquals(24, points.size());
        for (Vector point : points) {
            double distance = Math.sqrt(point.getX() * point.getX() + point.getZ() * point.getZ());
            assertEquals(2.0, distance, EPSILON, "every point sits on the radius");
            assertEquals(0.0, point.getY(), EPSILON, "a circle is flat");
        }
    }

    @Test
    @DisplayName("a beam draws one more point than its steps, so it reaches its full height")
    void beamReachesItsHeight() {
        List<Vector> points = ShapePoints.of("beam", "height:3.0;points:20");

        // Twenty steps means twenty-one points: the base and the top both.
        assertEquals(21, points.size());
        assertEquals(0.0, points.get(0).getY(), EPSILON);
        assertEquals(3.0, points.get(points.size() - 1).getY(), EPSILON);
    }

    @Test
    @DisplayName("a spiral climbs while it turns")
    void spiralClimbs() {
        List<Vector> points = ShapePoints.of("spiral", "height:4.0;radius:1.0;turns:2;points:40");

        assertEquals(40, points.size());
        for (int i = 1; i < points.size(); i++) {
            assertTrue(points.get(i).getY() > points.get(i - 1).getY(),
                    "every point is higher than the last");
        }
        for (Vector point : points) {
            double distance = Math.sqrt(point.getX() * point.getX() + point.getZ() * point.getZ());
            assertEquals(1.0, distance, EPSILON, "a spiral keeps its radius");
        }
    }

    @Test
    @DisplayName("a tornado narrows from its base to its top")
    void tornadoNarrows() {
        List<Vector> points = ShapePoints.of("tornado", "radius:2.0;top_radius:0.2;points:40");

        double first = radiusOf(points.get(0));
        double last = radiusOf(points.get(points.size() - 1));
        assertEquals(2.0, first, 0.05, "it starts at its base radius");
        assertTrue(last < 0.4, "and ends near its top radius, got " + last);
    }

    // -------------------------------------------------------------- the fixes

    @Test
    @DisplayName("a sphere is centred on its anchor, so y: can move it")
    void sphereIsCentredOnTheAnchor() {
        List<Vector> points = ShapePoints.of("sphere", "radius:2.0;points:64");

        double top = points.stream().mapToDouble(Vector::getY).max().orElseThrow();
        double bottom = points.stream().mapToDouble(Vector::getY).min().orElseThrow();

        // ExyliaCommons added a hardcoded +1 here and ignored y: entirely, alone
        // among the twenty shapes. Centred on zero is what lets y: work at all;
        // the compiler supplies the +1 as this shape's default height, so an
        // existing file still draws it where it always did.
        assertEquals(2.0, top, 0.01, "the top is one radius above the anchor");
        assertEquals(-2.0, bottom, 0.01, "and the bottom one radius below");
    }

    @Test
    @DisplayName("a sphere really is a sphere")
    void sphereIsRound() {
        List<Vector> points = ShapePoints.of("sphere", "radius:1.5;points:100");

        for (Vector point : points) {
            assertEquals(1.5, point.length(), 0.01, "every point is one radius from the centre");
        }
    }

    @Test
    @DisplayName("a torus is centred on its anchor, with no hidden block of height")
    void torusHasNoHiddenOffset() {
        List<Vector> points = ShapePoints.of("torus", "radius:2.0;tube:0.5");

        double top = points.stream().mapToDouble(Vector::getY).max().orElseThrow();
        double bottom = points.stream().mapToDouble(Vector::getY).min().orElseThrow();

        // Commons added +1.0 on top of whatever y: said, which no other shape
        // did, so a torus never sat where the file asked.
        //
        // Compared loosely on purpose: with ten tube segments none of them lands
        // exactly on the crown, so the extreme is just under the tube radius.
        // What matters is that it straddles the anchor rather than sitting a
        // block above it.
        assertEquals(0.0, (top + bottom) / 2.0, EPSILON, "the tube is centred on the anchor");
        assertTrue(top > 0.4 && top <= 0.5, "and reaches its thickness above, got " + top);
    }

    @Test
    @DisplayName("a star with no points given still draws a star")
    void starSurvivesZeroPoints() {
        // Commons divided by this without a floor, so points:0 produced
        // infinities and drew the shape at the world origin.
        List<Vector> points = ShapePoints.of("star", "radius:1.5;spikes:5;points:0");

        assertTrue(points.size() > 0);
        for (Vector point : points) {
            assertTrue(Double.isFinite(point.getX()) && Double.isFinite(point.getZ()),
                    "no point may be infinite");
        }
    }

    @Test
    @DisplayName("a sphere of one point does not divide by zero")
    void sphereSurvivesOnePoint() {
        List<Vector> points = ShapePoints.of("sphere", "radius:1.0;points:1");

        assertEquals(1, points.size());
        assertTrue(Double.isFinite(points.get(0).getY()));
    }

    // ------------------------------------------------------------ new features

    @Test
    @DisplayName("a helix can have more than two strands")
    void helixTakesAnyNumberOfStrands() {
        List<Vector> two = ShapePoints.of("double_helix", "points:20");
        List<Vector> three = ShapePoints.of("double_helix", "points:20;strands:3");

        assertEquals(40, two.size(), "two strands by default, as before");
        assertEquals(60, three.size(), "and three when asked");
    }

    @Test
    @DisplayName("a disc can be told how detailed to be")
    void discTakesAPointCount() {
        List<Vector> derived = ShapePoints.of("disc", "radius:2.0;rings:4");
        List<Vector> explicit = ShapePoints.of("disc", "radius:2.0;rings:4;points:10");

        // Commons derived this from the radius with no way to override it, so a
        // wide disc was always expensive.
        assertEquals(41, explicit.size(), "four rings of ten, plus the centre");
        assertNotEquals(derived.size(), explicit.size());
    }

    @Test
    @DisplayName("an angle is written in degrees")
    void anglesAreDegrees() {
        List<Vector> straight = ShapePoints.of("cross", "arms:1;radius:1.0;points:1;angle:0");
        List<Vector> quarter = ShapePoints.of("cross", "arms:1;radius:1.0;points:1;angle:90");

        Vector alongX = straight.get(straight.size() - 1);
        Vector alongZ = quarter.get(quarter.size() - 1);
        assertEquals(1.0, alongX.getX(), EPSILON);
        assertEquals(0.0, alongX.getZ(), EPSILON);
        // Ninety degrees, not ninety radians.
        assertEquals(0.0, alongZ.getX(), EPSILON);
        assertEquals(1.0, alongZ.getZ(), EPSILON);
    }

    @Test
    @DisplayName("claws droop as they reach outward")
    void clawsDroop() {
        List<Vector> points = ShapePoints.of("claw", "claws:1;radius:2.0;points:10;drop:0.5");

        assertEquals(0.0, points.get(0).getY(), EPSILON, "they start level");
        assertTrue(points.get(points.size() - 1).getY() < 0, "and end below the anchor");
    }

    @Test
    @DisplayName("wings arch up and come back down")
    void wingsArch() {
        List<Vector> points = ShapePoints.of("wings", "span:2.0;arch:1.0;points:10");

        double highest = points.stream().mapToDouble(Vector::getY).max().orElseThrow();
        assertEquals(1.0, highest, 0.01, "the peak is the arch height");
        assertEquals(0.0, points.get(0).getY(), EPSILON, "and both ends meet the anchor");
    }

    @Test
    @DisplayName("every built-in shape produces finite points with its defaults")
    void everyShapeIsFiniteOutOfTheBox() {
        for (String name : new String[]{"circle", "sphere", "beam", "spiral", "double_helix",
                "tornado", "star", "cage", "disc", "vortex", "wave", "cross", "galaxy", "torus",
                "burst", "pyramid", "ring_pulse", "wings", "arch", "claw"}) {
            List<Vector> points = ShapePoints.of(name, "");
            assertTrue(points.size() > 0, name + " drew nothing");
            for (Vector point : points) {
                assertTrue(Double.isFinite(point.getX())
                                && Double.isFinite(point.getY())
                                && Double.isFinite(point.getZ()),
                        name + " produced a point that is not a number: " + point);
            }
        }
    }

    @Test
    @DisplayName("no shape divides by a zero the file supplied")
    void noShapeDividesByZero() {
        // Every count a file can write, set to zero at once. Commons crashed or
        // drew at the origin for several of these.
        String hostile = "points:0;rings:0;arms:0;beams:0;claws:0;columns:0;spikes:0;"
                + "segments:0;tube_segments:0;strands:0;turns:0;frequency:0";
        for (String name : new String[]{"circle", "sphere", "beam", "spiral", "double_helix",
                "tornado", "star", "cage", "disc", "vortex", "wave", "cross", "galaxy", "torus",
                "burst", "pyramid", "ring_pulse", "wings", "arch", "claw"}) {
            List<Vector> points = ShapePoints.of(name, hostile);
            for (Vector point : points) {
                assertTrue(Double.isFinite(point.getX())
                                && Double.isFinite(point.getY())
                                && Double.isFinite(point.getZ()),
                        name + " produced " + point + " from zeroed counts");
            }
        }
    }

    private static double radiusOf(Vector point) {
        return Math.sqrt(point.getX() * point.getX() + point.getZ() * point.getZ());
    }
}
