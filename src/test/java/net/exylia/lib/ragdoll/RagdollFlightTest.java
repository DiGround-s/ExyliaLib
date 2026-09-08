package net.exylia.lib.ragdoll;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.internal.RagdollFlight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                double moved = Math.abs(flight.x()[last] - flight.x()[0])
                        + Math.abs(flight.y()[last] - flight.y()[0])
                        + Math.abs(flight.z()[last] - flight.z()[0]);
                assertTrue(moved > 0.2,
                        pose + "/" + part + " never went anywhere");
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
}
