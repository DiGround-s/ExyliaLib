package net.exylia.lib.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The poses a described movement turns into.
 *
 * <p>This is the arithmetic every display effect is built on, and all of it runs
 * without a server, so it can be checked exactly. The pose count in particular
 * is not cosmetic: too few and a spin visibly goes backwards, too many and every
 * viewer pays for packets nobody can see.
 */
class DisplayMotionTest {

    private static final float EPSILON = 1e-4f;

    @Test
    @DisplayName("a straight line needs two poses and nothing more")
    void straightLine() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(1000)
                .from(0, 6, 0)
                .to(0, 0, 0)
                .build();

        List<DisplayKeyframe> poses = motion.poses();
        assertEquals(2, poses.size());
        assertEquals(0L, poses.get(0).atMillis());
        assertEquals(1000L, poses.get(1).atMillis());
        assertEquals(6f, poses.get(0).y(), EPSILON);
        assertEquals(0f, poses.get(1).y(), EPSILON);
    }

    @Test
    @DisplayName("a spin is cut into pieces the client cannot take the wrong way round")
    void spinIsCutUp() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(2000)
                .spin(Rotation.Axis.Y, 3)
                .build();

        // Three turns at six poses a turn, plus the pose it starts from.
        assertEquals(19, motion.poses().size());

        // Every step is well under the half turn at which the shortest arc
        // starts going the wrong way. Measured as the angle between successive
        // rotations, which is what the client actually interpolates over.
        List<DisplayKeyframe> poses = motion.poses();
        for (int index = 1; index < poses.size(); index++) {
            double dot = dot(poses.get(index - 1).rotation(), poses.get(index).rotation());
            double angle = 2 * Math.acos(Math.min(1.0, Math.abs(dot)));
            assertTrue(angle < Math.PI, "step " + index + " turns " + angle + " radians");
        }
    }

    @Test
    @DisplayName("a fall is a curve, not a corner")
    void gravityCurves() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(1000)
                .gravity(20)
                .build();

        List<DisplayKeyframe> poses = motion.poses();
        assertTrue(poses.size() >= 8, "a fall needs enough poses to read as a curve");

        // Half of g t squared at the end, and nothing at the start.
        assertEquals(0f, poses.get(0).y(), EPSILON);
        assertEquals(-10f, poses.get(poses.size() - 1).y(), EPSILON);

        // Each step falls further than the one before it, which is what makes
        // it look like a fall rather than a slide.
        for (int index = 2; index < poses.size(); index++) {
            float previous = poses.get(index - 1).y() - poses.get(index - 2).y();
            float current = poses.get(index).y() - poses.get(index - 1).y();
            assertTrue(current < previous, "step " + index + " did not accelerate");
        }
    }

    @Test
    @DisplayName("a size that changes is interpolated across the poses")
    void scaleGrows() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(1000)
                .scale(0.2, 4.0)
                .gravity(0)
                .build();

        List<DisplayKeyframe> poses = motion.poses();
        assertEquals(0.2f, poses.get(0).scaleX(), EPSILON);
        assertEquals(4.0f, poses.get(poses.size() - 1).scaleY(), EPSILON);
    }

    @Test
    @DisplayName("turning a motion turns every pose and leaves the timing alone")
    void turnedByRotatesEveryPose() {
        DisplayMotion motion = DisplayMotion.builder().life(600).spin(Rotation.Axis.Y, 1).build();
        DisplayMotion turned = motion.turnedBy(Rotation.around(Rotation.Axis.Y, Math.PI / 2));

        assertEquals(motion.poses().size(), turned.poses().size());
        assertEquals(motion.lifeMillis(), turned.lifeMillis());
        for (int index = 0; index < motion.poses().size(); index++) {
            assertEquals(motion.poses().get(index).atMillis(),
                    turned.poses().get(index).atMillis());
        }
        // The first pose was not turning at all, so turning it is exactly the
        // rotation that was applied.
        assertEquals(Math.sin(Math.PI / 4), turned.poses().get(0).rotation().y(), EPSILON);
    }

    @Test
    @DisplayName("an eased movement is behind at the halfway mark and still lands on time")
    void easingMovesTheDistanceNotTheClock() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(1000)
                .from(0, 0, 0)
                .to(0, 10, 0)
                .ease(DisplayMotion.Easing.IN)
                .build();

        List<DisplayKeyframe> poses = motion.poses();
        DisplayKeyframe last = poses.get(poses.size() - 1);
        assertEquals(1000L, last.atMillis(), "easing must not change when it finishes");
        assertEquals(10f, last.y(), EPSILON, "nor where it finishes");

        // Half the time, well under half the distance: that is the wind-up.
        DisplayKeyframe halfway = poses.stream()
                .filter(pose -> pose.atMillis() >= 500L)
                .findFirst()
                .orElseThrow();
        assertTrue(halfway.y() < 3f, "an eased strike was already halfway there: " + halfway.y());
    }

    @Test
    @DisplayName("easing out is the same movement read backwards")
    void easingOutFrontLoads() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(1000)
                .to(0, 10, 0)
                .ease(DisplayMotion.Easing.OUT)
                .build();

        DisplayKeyframe halfway = motion.poses().stream()
                .filter(pose -> pose.atMillis() >= 500L)
                .findFirst()
                .orElseThrow();

        assertTrue(halfway.y() > 7f, "an impact settling was still on its way: " + halfway.y());
    }

    @Test
    @DisplayName("an eased spin is cut up finely enough for its fastest moment")
    void easedSpinStaysUnderTheLimit() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(1000)
                .spin(Rotation.Axis.Z, 2)
                .ease(DisplayMotion.Easing.IN)
                .build();

        List<DisplayKeyframe> poses = motion.poses();
        for (int index = 1; index < poses.size(); index++) {
            double dot = dot(poses.get(index - 1).rotation(), poses.get(index).rotation());
            double angle = 2 * Math.acos(Math.min(1.0, Math.abs(dot)));
            assertTrue(angle < Math.PI, "step " + index + " turns " + angle + " radians");
        }
    }

    @Test
    @DisplayName("a ring's own turn is applied after the pose, not before")
    void ringTurnComesLast() {
        // A blade rolled tip-down and then swung to face east. Rolling about
        // the world's axis instead of its own is what tips every blade of a
        // ring a different way, so the order is asserted rather than trusted.
        Rotation roll = Rotation.around(Rotation.Axis.Z, Math.PI / 2);
        Rotation yaw = Rotation.around(Rotation.Axis.Y, Math.PI / 2);

        DisplayKeyframe pose = new DisplayKeyframe(0L, 0f, 0f, 0f, roll, 1f, 1f, 1f);
        Rotation turned = pose.turnedBy(yaw).rotation();

        assertEquals(roll.then(yaw).x(), turned.x(), EPSILON);
        assertEquals(roll.then(yaw).y(), turned.y(), EPSILON);
        assertEquals(roll.then(yaw).z(), turned.z(), EPSILON);

        // The model's face, which starts due south, ends up due east whatever
        // the roll did to the blade in its own plane.
        float[] facing = turned.apply(new float[]{0f, 0f, 1f});
        assertEquals(1f, facing[0], EPSILON);
        assertEquals(0f, facing[2], EPSILON);
    }

    @Test
    @DisplayName("a size can differ per axis, which is how a cube becomes a plate")
    void scalePerAxis() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(400)
                .scale(new double[]{0.1, 0.1, 0.1}, new double[]{4.0, 0.2, 4.0})
                .build();

        DisplayKeyframe last = motion.poses().get(motion.poses().size() - 1);
        assertEquals(4.0f, last.scaleX(), EPSILON);
        assertEquals(0.2f, last.scaleY(), EPSILON);
        assertEquals(4.0f, last.scaleZ(), EPSILON);
    }

    @Test
    @DisplayName("turning by nothing hands back the same motion")
    void turningByNothingIsFree() {
        DisplayMotion motion = DisplayMotion.builder().life(500).build();

        assertSame(motion, motion.turnedBy(Rotation.NONE));
        assertSame(motion, motion.movedBy(0, 0, 0));
    }

    @Test
    @DisplayName("a wild number of turns is capped rather than sent")
    void spinIsCapped() {
        DisplayMotion motion = DisplayMotion.builder()
                .life(1000)
                .spin(Rotation.Axis.Y, 200)
                .build();

        assertTrue(motion.poses().size() <= 48,
                "a file asking for two hundred turns must not cost two hundred packets");
    }

    private static double dot(Rotation first, Rotation second) {
        return first.x() * second.x() + first.y() * second.y()
                + first.z() * second.z() + first.w() * second.w();
    }
}
