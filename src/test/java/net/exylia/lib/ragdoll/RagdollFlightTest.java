package net.exylia.lib.ragdoll;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.internal.RagdollFlight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a body coming apart must never do.
 *
 * <p>All three of these are invisible in a code review and unmistakable in
 * game: a piece that sinks through the floor, a piece that is still standing
 * where the body was, and a spin the client takes the long way round because
 * two poses were too far apart.
 */
class RagdollFlightTest {

    private static final RagdollBurst BURST = RagdollBurst.builder()
            .life(2.2)
            .intactFor(0.3)
            .speed(3.2)
            .up(6.5)
            .spread(0.45)
            .gravity(26)
            .bounce(0.32)
            .spin(1.8)
            .build();

    @Test
    @DisplayName("a body stands whole until it bursts, and then leaves")
    void standsThenLeaves() {
        for (RagdollPart part : RagdollPart.values()) {
            RagdollFlight.Flight flight =
                    RagdollFlight.solve(part, BURST, 1.0, Rotation.NONE, new Random(7));

            assertEquals(0L, flight.times()[0], part + " should have its first pose at zero");
            assertEquals(BURST.intactMillis(), flight.times()[1],
                    part + " should still be whole when the burst is due");
            assertEquals(part.blockCentreY(), flight.y()[0], 1e-6,
                    part + " should start where the body was standing");

            long previous = -1;
            for (long at : flight.times()) {
                assertTrue(at > previous || at == BURST.lifeMillis(),
                        part + " sends poses out of order");
                previous = at;
            }

            int last = flight.times().length - 1;
            double travelled = Math.hypot(flight.x()[last], flight.z()[last]);
            assertTrue(travelled > 0.2,
                    part + " never went anywhere: it is a body that fell apart in place");
        }
    }

    @Test
    @DisplayName("nothing sinks through the floor it died on")
    void staysAboveTheFloor() {
        for (int seed = 0; seed < 40; seed++) {
            for (RagdollPart part : RagdollPart.values()) {
                RagdollFlight.Flight flight =
                        RagdollFlight.solve(part, BURST, 1.0, Rotation.NONE, new Random(seed));
                for (double height : flight.y()) {
                    assertTrue(height >= 0,
                            part + " went under the floor, to " + height);
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
        RagdollBurst fastest = RagdollBurst.builder()
                .spin(RagdollFlight.MAX_TURNS_PER_SECOND * 4)
                .spread(1.0)
                .build();
        for (int seed = 0; seed < 40; seed++) {
            RagdollFlight.Flight flight = RagdollFlight.solve(
                    RagdollPart.ARM_LEFT, fastest, 1.0, Rotation.NONE, new Random(seed));
            Rotation[] rotations = flight.rotations();
            for (int index = 1; index < rotations.length; index++) {
                double dot = Math.abs(dot(rotations[index - 1], rotations[index]));
                // cos(half the angle between them). Half a turn is a dot of 0.
                assertTrue(dot > 0.05,
                        "two poses are " + Math.toDegrees(2 * Math.acos(Math.min(1, dot)))
                                + " degrees apart, so the client will take the long way round");
            }
        }
    }

    private static double dot(Rotation one, Rotation other) {
        return one.x() * other.x() + one.y() * other.y()
                + one.z() * other.z() + one.w() * other.w();
    }
}
