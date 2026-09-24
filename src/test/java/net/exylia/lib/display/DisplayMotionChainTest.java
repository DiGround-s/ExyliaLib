package net.exylia.lib.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Movements played back to back, and the overshooting curves.
 */
class DisplayMotionChainTest {

    private static DisplayMotion rise() {
        return DisplayMotion.builder().life(150).to(0, 0.6, 0).spin(Rotation.Axis.Y, 1)
                .ease(DisplayMotion.Easing.OUT).build();
    }

    private static DisplayMotion fall() {
        return DisplayMotion.builder().life(300).from(0, 0.6, 0).to(0, -0.2, 0)
                .spin(Rotation.Axis.Y, 2).ease(DisplayMotion.Easing.IN).build();
    }

    /** The angle between two rotations, in radians. */
    private static double between(Rotation a, Rotation b) {
        double dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
        return 2 * Math.acos(Math.min(1.0, dot));
    }

    @Test
    @DisplayName("a chain plays each segment after the last, in strictly increasing time")
    void timesAreMonotonic() {
        DisplayMotion chained = DisplayMotion.chain(rise(), fall());

        assertEquals(450, chained.lifeMillis());
        List<DisplayKeyframe> poses = chained.poses();
        assertEquals(0, poses.get(0).atMillis());
        assertEquals(450, poses.get(poses.size() - 1).atMillis());
        for (int index = 1; index < poses.size(); index++) {
            assertTrue(poses.get(index).atMillis() > poses.get(index - 1).atMillis(),
                    "pose " + index + " is not after the one before it");
        }
        assertEquals(-0.2f, poses.get(poses.size() - 1).y(), 1e-5);
    }

    @Test
    @DisplayName("a chained spin is still cut small enough to turn the right way")
    void spinStillCut() {
        List<DisplayKeyframe> poses = DisplayMotion.chain(rise(), fall()).poses();
        for (int index = 1; index < poses.size(); index++) {
            double angle = between(poses.get(index - 1).rotation(), poses.get(index).rotation());
            assertTrue(angle < Math.PI / 2, "pose " + index + " turns " + Math.toDegrees(angle));
        }
    }

    @Test
    @DisplayName("a segment that holds still holds: the next one waits for the hold to end")
    void holdsAtTheSeam() {
        DisplayMotion pop = DisplayMotion.builder().life(100).to(0, 1, 0).build();
        DisplayMotion chained = DisplayMotion.chain(pop, DisplayMotion.still(400), fall());

        List<DisplayKeyframe> poses = chained.poses();
        assertEquals(800, chained.lifeMillis());
        DisplayKeyframe held = poses.stream().filter(pose -> pose.atMillis() == 500)
                .findFirst().orElseThrow();
        assertEquals(1f, held.y(), 1e-6, "still where the pop left it when the fall begins");
        assertTrue(poses.stream().noneMatch(pose -> pose.atMillis() > 100 && pose.atMillis() < 500),
                "nothing moves during the hold");
    }

    @Test
    @DisplayName("a chain needs something to chain")
    void emptyChainRefused() {
        assertThrows(IllegalArgumentException.class, DisplayMotion::chain);
    }

    @Test
    @DisplayName("back, bounce and elastic are read from files, and back overshoots")
    void overshootingCurves() {
        assertEquals(DisplayMotion.Easing.BACK, DisplayMotion.Easing.of("back"));
        assertEquals(DisplayMotion.Easing.BACK, DisplayMotion.Easing.of("overshoot"));
        assertEquals(DisplayMotion.Easing.BOUNCE, DisplayMotion.Easing.of("Bounce"));
        assertEquals(DisplayMotion.Easing.ELASTIC, DisplayMotion.Easing.of("spring"));
        assertEquals(DisplayMotion.Easing.LINEAR, DisplayMotion.Easing.of("wobble"));

        DisplayMotion spike = DisplayMotion.builder().life(400).from(0, -0.6, 0).to(0, 0.5, 0)
                .ease(DisplayMotion.Easing.BACK).build();
        assertTrue(spike.poses().stream().anyMatch(pose -> pose.y() > 0.5f),
                "a back ease goes past its target before it settles");
        assertEquals(0.5f, spike.poses().get(spike.poses().size() - 1).y(), 1e-5);
        assertTrue(DisplayMotion.builder().ease(DisplayMotion.Easing.BOUNCE).build().poses().size()
                > DisplayMotion.builder().ease(DisplayMotion.Easing.OUT).build().poses().size(),
                "a bounce needs more poses than a plain ease to read as hops");
    }
}
