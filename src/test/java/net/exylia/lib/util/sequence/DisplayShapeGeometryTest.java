package net.exylia.lib.util.sequence;

import net.exylia.lib.util.sequence.internal.ShapePoints;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five shapes the display work asked for.
 *
 * <p>Their maths is worth asserting for the same reason the other twenty are:
 * a shape that drifts half a block or collapses to a point is a visible bug and
 * an invisible code change. The scatter has a second reason, which is that
 * anything drawn from a generator has to produce the same points twice.
 */
class DisplayShapeGeometryTest {

    private static final double EPSILON = 1e-6;

    @Test
    @DisplayName("the shapes do not use a word a display line already owns")
    void noParameterCollides() {
        // A display line reads size: as the model's own scale and rise: as
        // where it ends up. A shape that also read them would mean two things
        // by one word, and the file would have no way to say either.
        java.util.Set<String> display = java.util.Set.of(
                "as", "size", "size_to", "life", "from", "to", "rise", "spin", "axis",
                "tilt", "roll", "turn", "face_out", "gravity", "glow", "light", "model",
                "billboard", "hold", "pull", "ease", "orbit", "vary");
        for (String shape : java.util.List.of("dome", "cube", "line", "ribbon", "scatter")) {
            for (String parameter : net.exylia.lib.util.sequence.internal.Shapes
                    .parametersOf(shape)) {
                assertTrue(!display.contains(parameter),
                        shape + " reads \"" + parameter + "\", which a display line already owns");
            }
        }
    }

    @Test
    @DisplayName("a dome is the half of a sphere anybody was looking at")
    void domeIsAboveGround() {
        List<Vector> points = ShapePoints.of("dome", "radius:3;points:40");

        assertEquals(40, points.size());
        for (Vector point : points) {
            assertTrue(point.getY() >= -EPSILON, "a dome point was below the anchor: " + point);
            assertTrue(point.length() <= 3.0 + EPSILON, "a dome point left the radius: " + point);
        }
        // It reaches the top and it reaches the rim; a dome that is only a cap
        // or only a ring is the failure worth naming.
        assertTrue(points.stream().anyMatch(p -> p.getY() > 2.7), "no point near the top");
        assertTrue(points.stream().anyMatch(p -> p.getY() < 0.4), "no point near the rim");
    }

    @Test
    @DisplayName("a cube stands on the anchor and is square")
    void cubeIsSquareAndGrounded() {
        List<Vector> points = ShapePoints.of("cube", "width:2;points:4");

        assertEquals(48, points.size(), "twelve edges, four points each");
        for (Vector point : points) {
            assertTrue(point.getY() >= -EPSILON && point.getY() <= 2.0 + EPSILON,
                    "a cube point left its own height: " + point);
            assertTrue(Math.abs(point.getX()) <= 1.0 + EPSILON, "a cube point left its width");
        }
        // Its base sits on the anchor rather than straddling it, so a cube in a
        // file stands on the ground like every block a player has ever placed.
        assertTrue(points.stream().anyMatch(p -> Math.abs(p.getY()) < EPSILON));
    }

    @Test
    @DisplayName("a line runs from the anchor to where it was aimed")
    void lineRunsWhereItIsAimed() {
        List<Vector> points = ShapePoints.of("line", "length:6;dir:90;climb:2;points:7");

        assertEquals(7, points.size());
        assertEquals(0.0, points.get(0).length(), EPSILON, "a line starts at the anchor");

        Vector end = points.get(points.size() - 1);
        assertEquals(6.0, end.getX(), 1e-4, "ninety degrees is due east");
        assertEquals(2.0, end.getY(), EPSILON);
        assertEquals(0.0, end.getZ(), 1e-4);
    }

    @Test
    @DisplayName("a ribbon is a ring with a wave in it")
    void ribbonWaves() {
        List<Vector> points = ShapePoints.of("ribbon", "radius:2;points:24;waves:3;amplitude:0.8");

        assertEquals(24, points.size());
        for (Vector point : points) {
            double flat = Math.hypot(point.getX(), point.getZ());
            assertEquals(2.0, flat, 1e-6, "a ribbon is still a ring seen from above");
            assertTrue(Math.abs(point.getY()) <= 0.8 + EPSILON);
        }
        assertTrue(points.stream().anyMatch(p -> p.getY() > 0.7), "the wave never rose");
        assertTrue(points.stream().anyMatch(p -> p.getY() < -0.7), "the wave never fell");
    }

    @Test
    @DisplayName("a scatter looks unplanned and is the same every time")
    void scatterIsRepeatable() {
        List<Vector> first = ShapePoints.of("scatter", "radius:3;height:2;points:30;seed:7");
        List<Vector> again = ShapePoints.of("scatter", "radius:3;height:2;points:30;seed:7");
        List<Vector> other = ShapePoints.of("scatter", "radius:3;height:2;points:30;seed:8");

        assertEquals(30, first.size());
        // The same seed twice: a sequence is compiled once and played by every
        // kill on the server, so an effect that differs between two compiles is
        // an effect nobody can describe or test.
        for (int index = 0; index < first.size(); index++) {
            assertEquals(first.get(index), again.get(index), "seed 7 gave two different shapes");
        }
        assertTrue(first.stream().anyMatch(p -> !other.contains(p)), "seed 8 gave the same shape");

        for (Vector point : first) {
            assertTrue(Math.hypot(point.getX(), point.getZ()) <= 3.0 + EPSILON);
            assertTrue(point.getY() >= -EPSILON && point.getY() <= 2.0 + EPSILON);
        }
    }

    @Test
    @DisplayName("a scatter can be flattened onto the ground")
    void scatterCanBeFlat() {
        List<Vector> points = ShapePoints.of("scatter", "radius:2;points:15;seed:3;floor:true");

        for (Vector point : points) {
            assertEquals(0.0, point.getY(), EPSILON);
        }
    }

    @Test
    @DisplayName("every count set to zero at once still draws something")
    void zeroCountsAreFloored() {
        assertTrue(ShapePoints.of("dome", "radius:0;points:0").size() >= 1);
        assertTrue(ShapePoints.of("cube", "width:0;points:0").size() >= 1);
        assertTrue(ShapePoints.of("line", "length:0;points:0").size() >= 1);
        assertTrue(ShapePoints.of("ribbon", "radius:0;points:0").size() >= 1);
        assertTrue(ShapePoints.of("scatter", "radius:0;points:0").size() >= 1);
    }
}
