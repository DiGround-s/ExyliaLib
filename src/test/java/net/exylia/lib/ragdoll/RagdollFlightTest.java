package net.exylia.lib.ragdoll;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.internal.RagdollFlight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a body must never do, whichever death it was given.
 *
 * <p>All of these are invisible in a code review and unmistakable in game: a
 * piece that sinks through the floor, a piece still standing where the body
 * was, a spin the client takes the long way round because two poses were too
 * far apart, and a blow that lands on nothing.
 */
class RagdollFlightTest {

    private static RagdollMotion.Builder base() {
        return RagdollMotion.builder()
                .life(3.0)
                .intactFor(0.3)
                .speed(3.2)
                .up(6.5)
                .spread(0.45)
                .gravity(26)
                .bounce(0.32)
                .spin(1.8);
    }

    private static RagdollMotion posed(RagdollPose pose) {
        return base().pose(pose).build();
    }

    @Test
    @DisplayName("every pose stands whole until its moment, and then moves")
    void standsThenMoves() {
        for (RagdollPose pose : RagdollPose.values()) {
            // A choreography moves exactly as far as its frames say, and this
            // one has none. RagdollAnimationTest is where it is made to move.
            if (pose == RagdollPose.ANIMATE) {
                continue;
            }
            RagdollMotion motion = posed(pose);
            for (RagdollPart part : RagdollPart.values()) {
                RagdollFlight.Flight flight =
                        RagdollFlight.solve(part, motion, 1.0, Rotation.NONE, new Random(7));

                assertEquals(0L, flight.times()[0],
                        pose + "/" + part + " should have its first pose at zero");
                assertEquals(motion.intactMillis(), flight.times()[1],
                        pose + "/" + part + " should still be whole when its moment comes");
                assertEquals(part.blockCentreY(), flight.y()[0], 1e-6,
                        pose + "/" + part + " should start where the body was standing");

                long previous = -1;
                for (long at : flight.times()) {
                    assertTrue(at > previous || at == motion.lifeMillis(),
                            pose + "/" + part + " sends poses out of order");
                    previous = at;
                }

                int last = flight.times().length - 1;
                // Moved, or grown, or flattened: a swelling head never leaves
                // the neck it is on, and it is the least still thing in the
                // module.
                double changed = Math.abs(flight.x()[last] - flight.x()[0])
                        + Math.abs(flight.y()[last] - flight.y()[0])
                        + Math.abs(flight.z()[last] - flight.z()[0])
                        + Math.abs(flight.scales()[last][0] - 1)
                        + Math.abs(flight.scales()[last][1] - 1)
                        + Math.abs(flight.scales()[last][2] - 1);
                assertTrue(changed > 0.2,
                        pose + "/" + part + " never had anything happen to it");
            }
        }
    }

    @Test
    @DisplayName("nothing sinks through the floor it died on")
    void staysAboveTheFloor() {
        for (RagdollPose pose : RagdollPose.values()) {
            RagdollMotion motion = posed(pose);
            for (int seed = 0; seed < 25; seed++) {
                for (RagdollPart part : RagdollPart.values()) {
                    RagdollFlight.Flight flight = RagdollFlight.solve(
                            part, motion, 1.0, Rotation.NONE, new Random(seed));
                    for (double height : flight.y()) {
                        assertTrue(height >= 0,
                                pose + "/" + part + " went under the floor, to " + height);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a dive stops at the floor instead of going through it")
    void aDiveStops() {
        // A negative climb is how a file writes a dive. Nothing stops it but
        // this, and a piece under the floor is a piece nobody ever sees again.
        RagdollMotion diving = base()
                .pose(RagdollPose.PLANE)
                .rise(4.5)
                .up(-3.0)
                .speed(3.4)
                .build();
        for (RagdollPart part : RagdollPart.values()) {
            RagdollFlight.Flight flight =
                    RagdollFlight.solve(part, diving, 1.0, Rotation.NONE, new Random(11));
            for (double height : flight.y()) {
                assertTrue(height >= 0, part + " dived through the floor, to " + height);
            }
        }
    }

    @Test
    @DisplayName("no two poses are more than half a turn apart")
    void neverSpinsBackwards() {
        // The client interpolates rotation along the shortest arc. Two poses
        // more than 180 degrees apart therefore spin the wrong way, which reads
        // as a limb snapping back on itself once every tenth of a second.
        for (RagdollPose pose : RagdollPose.values()) {
            RagdollMotion motion = base()
                    .pose(pose)
                    .spin(RagdollFlight.MAX_TURNS_PER_SECOND * 4)
                    .spread(1.0)
                    .turns(0.4)
                    .build();
            for (int seed = 0; seed < 25; seed++) {
                RagdollFlight.Flight flight = RagdollFlight.solve(
                        RagdollPart.ARM_LEFT, motion, 1.0, Rotation.NONE, new Random(seed));
                Rotation[] rotations = flight.rotations();
                for (int index = 1; index < rotations.length; index++) {
                    double dot = Math.abs(dot(rotations[index - 1], rotations[index]));
                    // cos(half the angle between them). Half a turn is a dot of 0.
                    assertTrue(dot > 0.05, pose + ": two poses are "
                            + Math.toDegrees(2 * Math.acos(Math.min(1, dot)))
                            + " degrees apart, so the client takes the long way round");
                }
            }
        }
    }

    @Test
    @DisplayName("a blow shoves the body, and lands when the file says it does")
    void blowsLand() {
        RagdollMotion motion = base()
                .pose(RagdollPose.KNOCKED)
                .hits(3)
                .every(0.4)
                .force(1.0)
                .hang(1.6)
                .build();
        assertEquals(750L, motion.hitAt(0), "the first blow lands when the lift ends");
        assertEquals(1150L, motion.hitAt(1), "and the next one a beat later");

        RagdollFlight.Flight flight = RagdollFlight.solve(
                RagdollPart.TORSO, motion, 1.0, Rotation.NONE, new Random(3));

        // Where the chest hangs just before the first blow, and just after it.
        double before = horizontal(flight, at(flight, motion.hitAt(0) - 100));
        double after = horizontal(flight, at(flight, motion.hitAt(0) + 100));
        assertTrue(after > before + 0.2,
                "the blow moved the body by " + (after - before) + " blocks, which is nothing");
    }

    /** The pose nearest a moment. */
    private static int at(RagdollFlight.Flight flight, long millis) {
        int nearest = 0;
        for (int index = 0; index < flight.times().length; index++) {
            if (Math.abs(flight.times()[index] - millis)
                    < Math.abs(flight.times()[nearest] - millis)) {
                nearest = index;
            }
        }
        return nearest;
    }

    /** How far a piece is from the middle of the body, on the ground plane. */
    private static double horizontal(RagdollFlight.Flight flight, int index) {
        return Math.hypot(flight.x()[index], flight.z()[index]);
    }

    private static double dot(Rotation one, Rotation other) {
        return one.x() * other.x() + one.y() * other.y()
                + one.z() * other.z() + one.w() * other.w();
    }

    @Test
    @DisplayName("a limb that is opened out stays on the body it belongs to")
    void limbsStayAttached() {
        // The first version of every held pose moved the limbs outwards
        // instead of turning them about their joints. It reads as a body that
        // has already come apart and then frozen, which is the one thing these
        // poses are not.
        for (RagdollPose pose : List.of(RagdollPose.SPREAD, RagdollPose.KNOCKED,
                RagdollPose.PLANE, RagdollPose.HELICOPTER)) {
            RagdollMotion motion = base().pose(pose).open(1.0).rise(1.4).build();
            RagdollFlight.Flight chest =
                    RagdollFlight.solve(RagdollPart.TORSO, motion, 1.0, Rotation.NONE, new Random(5));
            for (RagdollPart limb : List.of(RagdollPart.ARM_LEFT, RagdollPart.ARM_RIGHT,
                    RagdollPart.LEG_LEFT, RagdollPart.LEG_RIGHT)) {
                RagdollFlight.Flight flight =
                        RagdollFlight.solve(limb, motion, 1.0, Rotation.NONE, new Random(5));
                // Only while it is being held: once it is let go the pieces
                // are meant to come apart, and that is a different test.
                long letGo = motion.intactMillis() + motion.liftMillis() + motion.hangMillis();
                for (int index = 0; index < flight.times().length; index++) {
                    if (pose.isHeld() && flight.times()[index] > letGo) {
                        break;
                    }
                    // The end of the limb nearest its joint, wherever the limb
                    // has been turned to and however far it has been stretched.
                    float reach = limb.blockHeight() / 2 * (float) flight.scales()[index][1];
                    float[] inner = flight.rotations()[index].apply(new float[]{0, reach, 0});
                    double gap = Math.sqrt(
                            Math.pow(flight.x()[index] + inner[0] - chest.x()[index], 2)
                                    + Math.pow(flight.y()[index] + inner[1] - chest.y()[index], 2)
                                    + Math.pow(flight.z()[index] + inner[2] - chest.z()[index], 2));
                    assertTrue(gap < 0.85, pose + ": " + limb + " is " + gap
                            + " blocks from the chest, which is off the body");
                }
            }
        }
    }

    @Test
    @DisplayName("a pose is arrived at, not jumped to")
    void nothingPops() {
        // A pose every tenth of a second and an ease that starts at full speed
        // put the limbs halfway open in one frame. Whatever a pose does, its
        // first step out of standing has to be a step.
        for (RagdollPose pose : List.of(RagdollPose.SPREAD, RagdollPose.KNOCKED,
                RagdollPose.VORTEX, RagdollPose.PLANE, RagdollPose.HELICOPTER,
                RagdollPose.FLATTEN, RagdollPose.MELT, RagdollPose.SIGN)) {
            RagdollMotion motion = base().pose(pose).open(1.0).rise(1.4).build();
            for (RagdollPart part : RagdollPart.values()) {
                RagdollFlight.Flight flight =
                        RagdollFlight.solve(part, motion, 1.0, Rotation.NONE, new Random(9));
                double step = Math.sqrt(
                        Math.pow(flight.x()[2] - flight.x()[1], 2)
                                + Math.pow(flight.y()[2] - flight.y()[1], 2)
                                + Math.pow(flight.z()[2] - flight.z()[1], 2));
                assertTrue(step < 0.34, pose + "/" + part + " moved " + step
                        + " blocks in its first tenth of a second, which is a jump");
            }
        }
    }

    @Test
    @DisplayName("a body that is thrown arrives in one piece")
    void aThrowStaysRigid() {
        // The whole point of the pose: a body put out of an airlock is still a
        // body all the way to the horizon. Every piece has to turn about the
        // same middle, and a tumble axis drawn per piece — which is what every
        // other pose does — would quietly turn it back into a cloud.
        RagdollMotion motion = base()
                .pose(RagdollPose.THROWN)
                .speed(7.5).up(3.2).gravity(0).spin(0.6)
                .build();
        RagdollFlight.Flight chest =
                RagdollFlight.solve(RagdollPart.TORSO, motion, 1.0, Rotation.NONE, new Random(2));
        for (RagdollPart part : RagdollPart.values()) {
            RagdollFlight.Flight flight =
                    RagdollFlight.solve(part, motion, 1.0, Rotation.NONE, new Random(31));
            double rest = 0;
            for (int index = 0; index < flight.times().length; index++) {
                double gap = Math.sqrt(
                        Math.pow(flight.x()[index] - chest.x()[index], 2)
                                + Math.pow(flight.y()[index] - chest.y()[index], 2)
                                + Math.pow(flight.z()[index] - chest.z()[index], 2));
                if (index == 0) {
                    rest = gap;
                    continue;
                }
                assertEquals(rest, gap, 0.02,
                        part + " drifted away from the chest on the way out");
            }
        }
    }

    @Test
    @DisplayName("a body goes where the file aimed it")
    void aThrowCanBeAimed() {
        // An effect draws its airlock at a fixed offset and then throws the
        // body away from whoever killed them, which is a body leaving somebody
        // else's scene. Zero degrees is east, ninety is south, the same as
        // every shape in the same file.
        for (double[] aim : new double[][]{{0, 1, 0}, {90, 0, 1}, {180, -1, 0}, {-90, 0, -1}}) {
            RagdollMotion motion = base()
                    .pose(RagdollPose.THROWN)
                    .speed(8).up(0).gravity(0).spin(0)
                    .heading(aim[0])
                    .build();
            RagdollFlight.Flight flight = RagdollFlight.solve(
                    RagdollPart.TORSO, motion, 1.0,
                    // Facing somewhere else entirely, which must not matter.
                    Rotation.around(Rotation.Axis.Y, 1.2), new Random(4));
            int last = flight.times().length - 1;
            double travelled = Math.hypot(flight.x()[last], flight.z()[last]);
            assertTrue(travelled > 4, "it barely moved: " + travelled);
            assertEquals(aim[1], flight.x()[last] / travelled, 0.02,
                    "thrown at " + aim[0] + " degrees, it went east by " + flight.x()[last]);
            assertEquals(aim[2], flight.z()[last] / travelled, 0.02,
                    "thrown at " + aim[0] + " degrees, it went south by " + flight.z()[last]);
        }
    }
}
