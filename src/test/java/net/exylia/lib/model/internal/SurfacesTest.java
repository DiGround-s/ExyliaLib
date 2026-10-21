package net.exylia.lib.model.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Feet standing on whatever the model's up points away from: a floor, or a wall. */
class SurfacesTest {

    /** Solid wherever {@code z >= wall(x)}: a wall to the south, its face where the function says. */
    private static Surfaces.Probe southWall(java.util.function.DoubleUnaryOperator wall) {
        return (x, y, z, dx, dy, dz, max) -> {
            assertEquals(1, dz, 1e-9, "looked for along the model's down, into the wall");
            double distance = wall.applyAsDouble(x) - z;
            return distance >= 0 && distance <= max ? distance : Double.NaN;
        };
    }

    @Test
    @DisplayName("a model stood up a wall in front of it has the wall for ground")
    void wallInFront() {
        double[] facing = Poser.facing(0, 90);
        double[] up = Poser.rotate(facing[0], facing[1], facing[2], facing[3], 0, 1, 0);
        assertEquals(-1, up[2], 1e-9, "facing south and tilted up, its up points north, away from the wall");
        double[] front = Poser.rotate(facing[0], facing[1], facing[2], facing[3], 0, 0, -1);
        assertEquals(1, front[1], 1e-9, "its front points up the wall");

        Legs.Ground flush = Surfaces.frame(facing, 0, 64, 0, southWall(x -> 0));
        Legs.Ground off = Surfaces.frame(facing, 0, 64, 0, southWall(x -> 0.3));
        assertEquals(0, flush.top(0, 0, 0, 0.5), 1e-9, "feet on the wall's face");
        assertEquals(-0.3, off.top(0, 0, 0, 0.5), 1e-9, "the wall a little further: the feet reach down to it");
        assertTrue(Double.isNaN(Surfaces.frame(facing, 0, 64, 0, southWall(x -> 5)).top(0, 0, 0, 0.5)),
                "nothing within reach");
    }

    @Test
    @DisplayName("turned west and stood up, its up points east")
    void anyYaw() {
        double[] facing = Poser.facing(90, 90);
        double[] up = Poser.rotate(facing[0], facing[1], facing[2], facing[3], 0, 1, 0);
        assertEquals(1, up[0], 1e-9);
        double[] level = Poser.facing(0, 0);
        double[] floorUp = Poser.rotate(level[0], level[1], level[2], level[3], 0, 1, 0);
        assertEquals(1, floorUp[1], 1e-9, "untilted, up is up");
    }

    @Test
    @DisplayName("a step on the wall under one leg lifts that foot along the wall, as a step on the floor does")
    void legsOnAWall() {
        Rig rig = LegsTest.biped();
        Poser poser = new Poser(rig);
        Legs legs = Legs.of(rig, poser);
        // The wall's face at z = 0.5, and 0.25 nearer under the model's right, which is over the world's west.
        Legs.Ground wall = Surfaces.frame(Poser.facing(0, 90), 0.5, 64, 0.5,
                southWall(x -> x < 0.5 ? 0.25 : 0.5));
        for (int pose = 0; pose < 30; pose++) {
            legs.pose(poser, null, -1, 0, 0, true, 0, 1 / 16.0, 0, 0, 0, 0, new double[3], wall);
        }
        Legs.Limb right = legs.limbs().get(0);
        Legs.Limb left = legs.limbs().get(1);
        assertEquals(4, LegsTest.sole(poser, right)[1], 0.1, "the right foot stands on the step, four pixels up");
        assertEquals(0, LegsTest.sole(poser, left)[1], 0.1, "the left foot stays on the wall");
    }
}
