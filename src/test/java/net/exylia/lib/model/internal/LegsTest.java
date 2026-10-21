package net.exylia.lib.model.internal;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legs found by name, bent to reach the ground, and feet that stay where they were put down. */
class LegsTest {

    private static final double PX = 1e-6;

    /**
     * A biped: a body at hip height, and on each side an upper leg from the hip
     * to the knee and a lower leg from the knee to the ground, six pixels each.
     * Facing north as Blockbench has it, the right leg is at +X and the left at -X.
     */
    private static Rig biped() {
        List<Rig.Bone> bones = List.of(
                new Rig.Bone("body", "b", -1, new double[]{0, 12, 0}, new double[3]),
                new Rig.Bone("leg_right", "lr", 0, new double[]{2, 12, 0}, new double[3]),
                new Rig.Bone("shin_right", "sr", 1, new double[]{2, 6, 0}, new double[3]),
                new Rig.Bone("leg_left", "ll", 0, new double[]{-2, 12, 0}, new double[3]),
                new Rig.Bone("shin_left", "sl", 3, new double[]{-2, 6, 0}, new double[3]));
        Look stone = Look.block(Material.STONE);
        List<Rig.Part> parts = List.of(
                new Rig.Part("torso", 0, new double[]{0, 12, 0}, new double[3], new double[]{-3, 12, -1},
                        new double[]{3, 18, 1}, stone),
                new Rig.Part("thigh", 1, new double[]{2, 12, 0}, new double[3], new double[]{1, 6, -1},
                        new double[]{3, 12, 1}, stone),
                new Rig.Part("calf", 2, new double[]{2, 6, 0}, new double[3], new double[]{1, 0, -1},
                        new double[]{3, 6, 1}, stone),
                new Rig.Part("thigh", 3, new double[]{-2, 12, 0}, new double[3], new double[]{-3, 6, -1},
                        new double[]{-1, 12, 1}, stone),
                new Rig.Part("calf", 4, new double[]{-2, 6, 0}, new double[3], new double[]{-3, 0, -1},
                        new double[]{-1, 6, 1}, stone));
        return new Rig(bones, parts, new double[]{-3, 0, -1}, new double[]{3, 18, 1});
    }

    private static double distance(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    private static double[] sole(Poser poser, Legs.Limb limb) {
        return Legs.soleOf(poser.world(limb.end()), limb.sole());
    }

    @Test
    void aStraightLegBendsForwardToReachAHigherSpot() {
        double[] a = {0, 12, 0};
        double[] b = {0, 6, 0};
        double[] c = {0, 0, 0};
        double[] target = {0, 3, 0};
        Legs.Bend bend = Legs.bend(a, b, c, target, Legs.FORWARD);
        assertNotNull(bend);
        assertEquals(0, distance(bend.end(), target), 1e-6);
        assertEquals(6, distance(bend.knee(), a), 1e-6, "the upper leg keeps its length");
        assertEquals(6, distance(bend.end(), bend.knee()), 1e-6, "the lower leg keeps its length");
        assertTrue(bend.knee()[2] < 0, "the knee goes forwards, to the model's front at -Z");
        // The turn handed to the upper bone takes its old direction to its new one.
        double[] turned = Poser.rotate(bend.upper()[0], bend.upper()[1], bend.upper()[2], bend.upper()[3], 0, -6, 0);
        assertEquals(bend.knee()[0] - a[0], turned[0], 1e-6);
        assertEquals(bend.knee()[1] - a[1], turned[1], 1e-6);
        assertEquals(bend.knee()[2] - a[2], turned[2], 1e-6);
    }

    @Test
    void aBentLegKeepsBendingTheWayItBends() {
        // A knee already pointing backwards, like a bird's.
        Legs.Bend bend = Legs.bend(new double[]{0, 12, 0}, new double[]{0, 6, 2}, new double[]{0, 0, 0},
                new double[]{0, 2, 0}, Legs.FORWARD);
        assertNotNull(bend);
        assertTrue(bend.knee()[2] > 0);
    }

    @Test
    void aSpotOutOfReachIsReachedTowards() {
        double[] a = {0, 12, 0};
        Legs.Bend bend = Legs.bend(a, new double[]{0, 6, 0}, new double[]{0, 0, 0}, new double[]{0, -20, 0},
                Legs.FORWARD);
        assertNotNull(bend);
        assertEquals(12, distance(bend.end(), a), 1e-3, "as far as both bones reach, no further");
        assertEquals(0, bend.end()[0], 1e-6);
        assertEquals(0, bend.end()[2], 1e-6);
    }

    @Test
    void legsAreFoundByTheirNames() {
        assertTrue(Legs.isLeg("leg_right_0_low"));
        assertTrue(Legs.isLeg("Left.Foot"));
        assertFalse(Legs.isLeg("legend"));
        assertFalse(Legs.isLeg("arm_left"));
        assertTrue(Legs.isFoot("hoof-front"));
        assertFalse(Legs.isFoot("shin"));

        Rig rig = biped();
        Legs legs = Legs.of(rig, new Poser(rig));
        assertEquals(2, legs.limbs().size());
        Legs.Limb right = legs.limbs().get(0);
        assertEquals(2, right.end(), "the shin is where the leg ends");
        assertEquals(1, right.knee(), "and it bends at the knee, from the upper leg");
        assertFalse(right.foot());
        assertTrue(right.bends());
        assertEquals(0, right.restY(), 1e-6, "it stands on the ground");
        assertEquals(12, right.length(), 1e-6);
        assertEquals(2, right.restX(), 1e-6);
    }

    @Test
    void flatGroundChangesNothing() {
        Rig rig = biped();
        Poser plain = new Poser(rig);
        plain.bones(null, -1, 0, 0, 0, 0, 0);
        Poser standing = new Poser(rig);
        Legs legs = Legs.of(rig, standing);
        for (int pose = 0; pose < 8; pose++) {
            legs.pose(standing, null, -1, 0, 0, true, 0, 1 / 16.0, 0, 0, 64, 0, new double[3],
                    (x, z, y, reach) -> 64);
        }
        assertEquals(1, legs.weight(), PX);
        for (int bone = 0; bone < rig.bones().size(); bone++) {
            for (int axis = Poser.TX; axis <= Poser.TZ; axis++) {
                assertEquals(plain.world(bone)[axis], standing.world(bone)[axis], 0.05,
                        "bone " + rig.bones().get(bone).name());
            }
        }
    }

    @Test
    void aStepUnderOneLegLiftsThatFootAndTiltsTheBody() {
        Rig rig = biped();
        Poser poser = new Poser(rig);
        Legs legs = Legs.of(rig, poser);
        // Facing south the model is turned half round: its right (+X) is over the world's west (-X).
        Legs.Ground step = (x, z, y, reach) -> x < 0.5 ? y + 0.25 : y;
        for (int pose = 0; pose < 30; pose++) {
            legs.pose(poser, null, -1, 0, 0, true, 0, 1 / 16.0, 0, 0.5, 64, 0.5, new double[3], step);
        }
        Legs.Limb right = legs.limbs().get(0);
        Legs.Limb left = legs.limbs().get(1);
        assertEquals(4, sole(poser, right)[1], 0.1, "the right foot stands on the step, four pixels up");
        assertEquals(0, sole(poser, left)[1], 0.1, "the left foot stays on the floor");
        double[] hip = {poser.world(1)[Poser.TX], poser.world(1)[Poser.TY], poser.world(1)[Poser.TZ]};
        assertTrue(distance(hip, sole(poser, right)) < 11, "the right leg bent to get there");
    }

    @Test
    void aFootDownStaysOnItsSpotWhileTheBodyWalksOn() {
        Rig rig = biped();
        Poser poser = new Poser(rig);
        Legs legs = Legs.of(rig, poser);
        Legs.Ground flat = (x, z, y, reach) -> y;
        double z = 0;
        for (int pose = 0; pose < 4; pose++) {
            legs.pose(poser, null, -1, 0, 0, true, 2, 1 / 16.0, 0, 0, 64, z, new double[3], flat);
        }
        Legs.Limb right = legs.limbs().get(0);
        // Where the sole is in the world: facing south the model's -Z front is the world's +Z.
        double before = z - sole(poser, right)[2] / 16.0;
        z += 0.1;
        legs.pose(poser, null, -1, 0, 0, true, 2, 1 / 16.0, 0, 0, 64, z, new double[3], flat);
        double after = z - sole(poser, right)[2] / 16.0;
        assertEquals(before, after, 0.01, "the body moved a tenth of a block; the foot did not");

        // Further than a stride, it is put down again where the body now has it.
        z += 1;
        legs.pose(poser, null, -1, 0, 0, true, 2, 1 / 16.0, 0, 0, 64, z, new double[3], flat);
        assertEquals(z, z - sole(poser, right)[2] / 16.0, 0.05);
    }

    @Test
    void standingStillPlantsNothing() {
        Rig rig = biped();
        Poser poser = new Poser(rig);
        Legs legs = Legs.of(rig, poser);
        Legs.Ground flat = (x, z, y, reach) -> y;
        for (int pose = 0; pose < 4; pose++) {
            legs.pose(poser, null, -1, 0, 0, true, 0, 1 / 16.0, 0, 0, 64, 0, new double[3], flat);
        }
        legs.pose(poser, null, -1, 0, 0, true, 0, 1 / 16.0, 0, 0, 64, 0.1, new double[3], flat);
        assertEquals(0, sole(poser, legs.limbs().get(0))[2], 0.05, "a standing model's feet follow it");
    }
}
