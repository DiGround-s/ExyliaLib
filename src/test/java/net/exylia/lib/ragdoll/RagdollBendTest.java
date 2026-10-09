package net.exylia.lib.ragdoll;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.internal.RagdollFlight;
import net.exylia.lib.ragdoll.internal.RagdollPieces;
import net.exylia.lib.ragdoll.internal.RagdollRig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elbows, knees and the back: that each bends the way its name says, that the
 * feet stay on the floor when asked to, and that a pose written the vanilla
 * way lands where the vanilla model would put it.
 */
class RagdollBendTest {

    private static final double PX = RagdollPart.PIXEL;

    /** A point on a part, {@code along} blocks above its middle, bent if it is beyond the hinge. */
    private static double[] point(RagdollPart part, double[] pose, double along, boolean vanilla) {
        RagdollRig.Placed placed = RagdollRig.place(part, pose, 1.0, Rotation.NONE, 0, vanilla);
        boolean beyond = part == RagdollPart.TORSO ? along > 0 : along < 0;
        float[] local = {0f, (float) along, 0f};
        if (beyond) {
            local = placed.bend().apply(local);
        }
        float[] turned = placed.rotation().apply(local);
        return new double[]{placed.x() + turned[0], placed.y() + turned[1], placed.z() + turned[2]};
    }

    private static double[] minus(double[] one, double[] other) {
        return new double[]{one[0] - other[0], one[1] - other[1], one[2] - other[2]};
    }

    private static void near(double[] expected, double[] actual, double tolerance, String what) {
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(expected[axis], actual[axis], tolerance, what + " on axis " + axis);
        }
    }

    private static double[] pose(String keys) {
        List<String> problems = new ArrayList<>();
        RagdollAnimation animation = RagdollAnimation.parse("0 " + keys, problems::add);
        assertEquals(List.of(), problems);
        return animation.at(0);
    }

    // ------------------------------------------------------------ own terms

    @Test
    @DisplayName("an elbow bends the forearm forwards, and outwards when turned a quarter round")
    void elbowsBend() {
        double[] forward = pose("elbow_r=90");
        double[] elbow = point(RagdollPart.ARM_RIGHT, forward, 0, false);
        double[] hand = point(RagdollPart.ARM_RIGHT, forward, -6 * PX, false);
        near(new double[]{0, 0, 6 * PX}, minus(hand, elbow), 1e-4, "a forearm bent forwards");

        double[] out = pose("elbows=90,90");
        double[] right = minus(point(RagdollPart.ARM_RIGHT, out, -6 * PX, false),
                point(RagdollPart.ARM_RIGHT, out, 0, false));
        double[] left = minus(point(RagdollPart.ARM_LEFT, out, -6 * PX, false),
                point(RagdollPart.ARM_LEFT, out, 0, false));
        // The body's right is towards -X, its left towards +X.
        near(new double[]{-6 * PX, 0, 0}, right, 1e-4, "the right forearm turned out");
        near(new double[]{6 * PX, 0, 0}, left, 1e-4, "the left forearm turned out");
    }

    @Test
    @DisplayName("a knee bends the shin backwards")
    void kneesBend() {
        double[] kneeling = pose("knee_l=90");
        double[] shin = minus(point(RagdollPart.LEG_LEFT, kneeling, -6 * PX, false),
                point(RagdollPart.LEG_LEFT, kneeling, 0, false));
        near(new double[]{0, 0, -6 * PX}, shin, 1e-4, "a shin bent back");
        // The thigh did not move.
        near(point(RagdollPart.LEG_LEFT, RagdollRig.standing(), 6 * PX, false),
                point(RagdollPart.LEG_LEFT, kneeling, 6 * PX, false), 1e-6, "the hip");
    }

    @Test
    @DisplayName("the back bends at its middle and carries the head and the arms")
    void spineCarriesTheTop() {
        double[] standing = RagdollRig.standing();
        double[] bowed = pose("spine=60");
        RagdollRig.Placed head = RagdollRig.place(RagdollPart.HEAD, bowed, 1, Rotation.NONE, 0);
        assertTrue(head.z() > 8 * PX, "the head goes forwards with the chest: " + head.z());
        double[] shoulder = point(RagdollPart.ARM_LEFT, bowed, 4 * PX, false);
        assertTrue(shoulder[2] > 3 * PX, "the shoulder goes with it");
        // The hips stay where they are.
        near(point(RagdollPart.TORSO, standing, -6 * PX, false),
                point(RagdollPart.TORSO, bowed, -6 * PX, false), 1e-6, "the waist");

        double[] aside = pose("spine=40,90");
        RagdollRig.Placed leaning = RagdollRig.place(RagdollPart.HEAD, aside, 1, Rotation.NONE, 0);
        assertTrue(leaning.x() < -4 * PX, "turned a quarter round it bends to the body's right: " + leaning.x());
    }

    @Test
    @DisplayName("the head and arms never come off a bent chest")
    void aBentChestHolds() {
        Random random = new Random(4);
        for (int trial = 0; trial < 200; trial++) {
            double[] pose = RagdollRig.standing();
            pose[RagdollRig.FLIP] = random.nextDouble(-180, 180);
            pose[RagdollRig.TURN] = random.nextDouble(-180, 180);
            pose[RagdollRig.of(RagdollRig.BODY, RagdollRig.PITCH)] = random.nextDouble(-90, 90);
            pose[RagdollRig.of(RagdollRig.BODY, RagdollRig.BEND)] = random.nextDouble(-90, 90);
            pose[RagdollRig.of(RagdollRig.BODY, RagdollRig.BEND_AXIS)] = random.nextDouble(-180, 180);
            pose[RagdollRig.of(RagdollRig.HEAD, RagdollRig.PITCH)] = random.nextDouble(-90, 90);
            pose[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.ROLL)] = random.nextDouble(-90, 180);
            double[] neck = point(RagdollPart.TORSO, pose, 6 * PX, false);
            double[] chin = point(RagdollPart.HEAD, pose, -4 * PX, false);
            assertTrue(Math.sqrt(Math.pow(neck[0] - chin[0], 2) + Math.pow(neck[1] - chin[1], 2)
                    + Math.pow(neck[2] - chin[2], 2)) < 1e-3, "the head came off the neck");
        }
    }

    @Test
    @DisplayName("planted feet stay on the floor and the knees go forwards")
    void plantedFeetStay() {
        double[] squat = pose("up=-0.3 plant=1");
        for (RagdollPart leg : List.of(RagdollPart.LEG_LEFT, RagdollPart.LEG_RIGHT)) {
            double side = leg == RagdollPart.LEG_LEFT ? 1 : -1;
            double[] foot = point(leg, squat, -6 * PX, false);
            near(new double[]{side * 2 * PX, 0, 0}, foot, 1e-3, leg + "'s foot");
            double[] knee = point(leg, squat, 0, false);
            assertTrue(knee[2] > 2 * PX, leg + "'s knee should push forwards: " + knee[2]);
        }
        // A body that turns round steps round with it.
        double[] turned = pose("up=-0.2 plant=1 turn=90");
        double[] left = point(RagdollPart.LEG_LEFT, turned, -6 * PX, false);
        near(new double[]{0, 0, -2 * PX}, left, 1e-3, "the left foot, turned a quarter");
        // Half planted is half way between.
        double[] half = pose("up=-0.3 plant=0.5");
        double[] foot = point(RagdollPart.LEG_LEFT, half, -6 * PX, false);
        assertTrue(foot[1] < -0.05 && foot[1] > -0.3, "half planted sinks part of the way: " + foot[1]);
    }

    @Test
    @DisplayName("a named pose straightens every bend")
    void namedPosesStraighten() {
        List<String> problems = new ArrayList<>();
        RagdollAnimation animation = RagdollAnimation.parse("0.2 elbows=80 knees=40 spine=20 | 0.2 stand",
                problems::add);
        assertEquals(List.of(), problems);
        assertTrue(animation.bends());
        double[] stood = animation.at(400);
        for (int joint = RagdollRig.HEAD; joint <= RagdollRig.LEG_LEFT; joint++) {
            assertEquals(0, stood[RagdollRig.of(joint, RagdollRig.BEND)], 1e-9);
        }
    }

    // ------------------------------------------------------------ the vanilla way

    @Test
    @DisplayName("a vanilla body left alone stands where this rig's body stands")
    void vanillaStandsTheSame() {
        double[] standing = RagdollRig.standing();
        for (RagdollPart part : RagdollPart.values()) {
            RagdollRig.Placed own = RagdollRig.place(part, standing, 1, Rotation.NONE, 0, false);
            RagdollRig.Placed vanilla = RagdollRig.place(part, standing, 1, Rotation.NONE, 0, true);
            assertEquals(own.x(), vanilla.x(), 0.15 * PX, part + " across");
            assertEquals(own.y(), vanilla.y(), 1e-6, part + " up");
            assertEquals(own.z(), vanilla.z(), 0.15 * PX, part + " forward");
        }
    }

    @Test
    @DisplayName("a vanilla arm turned the way a zombie holds it points forwards")
    void vanillaArmsPointWhereTheModelDoes() {
        double[] pose = RagdollRig.standing();
        pose[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.PITCH)] = -90;
        double[] hand = point(RagdollPart.ARM_RIGHT, pose, -6 * PX, true);
        double[] shoulder = point(RagdollPart.ARM_RIGHT, pose, 6 * PX, true);
        assertTrue(hand[2] - shoulder[2] > 10 * PX, "the hand is in front");
        assertEquals(shoulder[1], hand[1], 1e-3, "and level with the shoulder");

        double[] raised = RagdollRig.standing();
        raised[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.ROLL)] = 90;
        assertTrue(point(RagdollPart.ARM_RIGHT, raised, -6 * PX, true)[0] < -12 * PX,
                "a positive roll lifts the right arm out to the right");

        double[] bowed = RagdollRig.standing();
        bowed[RagdollRig.of(RagdollRig.BODY, RagdollRig.BEND)] = 45;
        RagdollRig.Placed head = RagdollRig.place(RagdollPart.HEAD, bowed, 1, Rotation.NONE, 0, true);
        assertTrue(head.z() > 4 * PX, "a bent torso takes the head forwards: " + head.z());
    }

    @Test
    @DisplayName("a vanilla body is turned about its hips and moved in blocks")
    void vanillaBodyMoves() {
        double[] pose = RagdollRig.standing();
        pose[RagdollRig.FLIP] = 90;
        RagdollRig.Placed head = RagdollRig.place(RagdollPart.HEAD, pose, 1, Rotation.NONE, 0, true);
        assertTrue(head.z() > 0.8, "tipped forwards, the head is in front: " + head.z());

        double[] moved = RagdollRig.standing();
        moved[RagdollRig.UP] = 1;
        RagdollRig.Placed raised = RagdollRig.place(RagdollPart.HEAD, moved, 1, Rotation.NONE, 0, true);
        assertEquals(28 * PX + 1, raised.y(), 1e-6);
    }

    // ------------------------------------------------------------ drawing it

    @Test
    @DisplayName("a body that bends is cut in halves even at the lowest detail")
    void bendingBodiesAreCut() {
        List<String> problems = new ArrayList<>();
        RagdollAnimation flex = RagdollAnimation.parse("0.3 elbows=110 | 0.3 elbows=0", problems::add);
        RagdollMotion motion = RagdollMotion.builder().pose(RagdollPose.ANIMATE).animation(flex)
                .intactFor(0).life(1).build();
        List<RagdollPieces.Piece> pieces = RagdollPieces.solve(motion, 1, 1, Rotation.NONE, new Random(1));
        assertEquals(11, pieces.size(), "a head, and an upper and a lower half of everything else");

        RagdollAnimation still = RagdollAnimation.parse("0.3 arms=0,0,90", problems::add);
        RagdollMotion plain = RagdollMotion.builder().pose(RagdollPose.ANIMATE).animation(still)
                .intactFor(0).life(1).build();
        assertEquals(6, RagdollPieces.solve(plain, 1, 1, Rotation.NONE, new Random(1)).size(),
                "one that never bends is drawn as it always was");

        // The forearm turns with the elbow; the upper arm does not.
        RagdollPieces.Piece upper = null;
        RagdollPieces.Piece lower = null;
        for (RagdollPieces.Piece piece : pieces) {
            if (piece.part() == RagdollPart.ARM_RIGHT) {
                if (piece.cellY() == 0) {
                    upper = piece;
                } else {
                    lower = piece;
                }
            }
        }
        DisplayKeyframe upperBent = at(upper.poses(), 300);
        DisplayKeyframe lowerBent = at(lower.poses(), 300);
        assertNotEquals(upperBent.rotation(), lowerBent.rotation());
        assertTrue(lowerBent.z() > upperBent.z() + 2 * PX, "the forearm is in front of the upper arm");
    }

    @Test
    @DisplayName("a looping body with follow-through comes round to where its cycle began")
    void loopsCloseWithFollowThrough() {
        List<String> problems = new ArrayList<>();
        RagdollAnimation dance = RagdollAnimation.parse(
                "0.3 up=0.3 arms=0,0,40 | 0.3 up=0 arms=0,0,10 | 0.3 up=0.3 arms=0,0,40", problems::add);
        RagdollMotion motion = RagdollMotion.builder().pose(RagdollPose.ANIMATE).animation(dance)
                .intactFor(0).follow(1.5).breathe(1).loop(true).loopFrom(0.3).life(300).build();
        RagdollFlight.Flight arm = RagdollFlight.solve(RagdollPart.ARM_LEFT, motion, 1, Rotation.NONE, new Random(2));
        int from = 0;
        while (arm.times()[from] < 300) {
            from++;
        }
        int last = arm.times().length - 1;
        double gap = Math.sqrt(Math.pow(arm.x()[last] - arm.x()[from], 2)
                + Math.pow(arm.y()[last] - arm.y()[from], 2) + Math.pow(arm.z()[last] - arm.z()[from], 2));
        assertTrue(gap < 0.01, "the arm should end the cycle where it began it, not " + gap + " away");
    }

    private static DisplayKeyframe at(List<DisplayKeyframe> poses, long millis) {
        DisplayKeyframe found = poses.get(0);
        for (DisplayKeyframe pose : poses) {
            if (pose.atMillis() <= millis) {
                found = pose;
            }
        }
        return found;
    }
}
