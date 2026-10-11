package net.exylia.lib.display;

import net.exylia.lib.util.internal.Ease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curves a display moves along: every easing by name, one per channel,
 * paths through keyframes, and the poses a long eased spin is cut into.
 */
class MotionCurvesTest {

    @Test
    @DisplayName("every curve the library knows can be named on a display")
    void everyCurveIsExposed() {
        for (Ease curve : Ease.values()) {
            DisplayMotion.Easing easing = DisplayMotion.Easing.valueOf(curve.name());
            assertEquals(easing, DisplayMotion.Easing.of(curve.name().toLowerCase()));
            assertEquals(curve.at(0.37), easing.apply(0.37), 1e-12, curve.name());
        }
        assertEquals(Ease.values().length, DisplayMotion.Easing.values().length);
    }

    @Test
    @DisplayName("names are read in every spelling, and the old ones still mean what they meant")
    void spellings() {
        assertEquals(DisplayMotion.Easing.QUART_OUT, DisplayMotion.Easing.of("easeOutQuart"));
        assertEquals(DisplayMotion.Easing.QUART_OUT, DisplayMotion.Easing.of("quart-out"));
        assertEquals(DisplayMotion.Easing.IN, DisplayMotion.Easing.of("accelerate"));
        assertEquals(DisplayMotion.Easing.OUT, DisplayMotion.Easing.of("DECELERATE"));
        assertEquals(DisplayMotion.Easing.BACK, DisplayMotion.Easing.of("overshoot"));
        assertEquals(DisplayMotion.Easing.ELASTIC, DisplayMotion.Easing.of("spring"));
        assertEquals(DisplayMotion.Easing.LINEAR, DisplayMotion.Easing.of("no such curve"));
    }

    @Test
    @DisplayName("the offsets, the turn and the size can each have their own curve")
    void separateChannels() {
        DisplayMotion motion = DisplayMotion.builder().life(1000)
                .from(0, 0, 0).to(10, 0, 0)
                .scale(1, 3)
                .easePosition(DisplayMotion.Easing.QUAD_IN)
                .easeScale(DisplayMotion.Easing.LINEAR)
                .build();
        DisplayKeyframe middle = closestTo(motion.poses(), 500);
        double at = middle.atMillis() / 1000.0;

        // The pose's time is whole milliseconds, so allow for the rounding.
        assertEquals(10 * at * at, middle.x(), 0.02, "offsets follow quad_in");
        assertEquals(1 + 2 * at, middle.scaleX(), 0.01, "size stays linear");
    }

    @Test
    @DisplayName("snap jumps within the first tick instead of sliding across the life")
    void snapJumpsAtOnce() {
        DisplayMotion motion = DisplayMotion.builder().life(2000)
                .to(0, 3, 0).ease(DisplayMotion.Easing.SNAP).build();

        assertEquals(50L, motion.poses().get(1).atMillis());
        assertEquals(3f, motion.poses().get(1).y(), 1e-6);
    }

    @Test
    @DisplayName("turnTo ends exactly in the rotation it was given")
    void turnToLands() {
        Rotation end = Rotation.around(Rotation.Axis.Y, Math.PI / 2);
        DisplayMotion motion = DisplayMotion.builder().life(600).turnTo(end)
                .ease(DisplayMotion.Easing.SINE_IN_OUT).build();
        Rotation last = motion.poses().get(motion.poses().size() - 1).rotation();

        assertEquals(1.0, Math.abs(dot(last, end)), 1e-5);
    }

    @Test
    @DisplayName("a long eased spin gets more poses than a short one and never turns the wrong way")
    void longSpinIsCutWhereItTurns() {
        DisplayMotion wheel = DisplayMotion.builder().life(8000)
                .spin(Rotation.Axis.Z, 12)
                .ease(DisplayMotion.Easing.QUART_OUT)
                .build();
        List<DisplayKeyframe> poses = wheel.poses();

        assertTrue(poses.size() > 48, "more than the old ceiling: " + poses.size());
        assertTrue(poses.size() <= DisplayMotion.maxPoses(8000));
        for (int index = 1; index < poses.size(); index++) {
            double angle = 2 * Math.acos(Math.min(1.0,
                    Math.abs(dot(poses.get(index - 1).rotation(), poses.get(index).rotation()))));
            assertTrue(angle < Math.PI * 0.75, "step " + index + " turns " + Math.toDegrees(angle));
        }
    }

    @Test
    @DisplayName("the pose ceiling grows with the life, never below what it was")
    void ceilingScales() {
        assertEquals(48, DisplayMotion.maxPoses(500));
        assertEquals(48, DisplayMotion.maxPoses(1000));
        assertEquals(201, DisplayMotion.maxPoses(10_000));
        assertEquals(256, DisplayMotion.maxPoses(60_000));
    }

    @Test
    @DisplayName("a spline runs through every keyframe without a corner at any of them")
    void splinePassesThroughKeys() {
        List<DisplayKeyframe> keys = List.of(
                key(0, 0, 0), key(500, 2, 1), key(1000, 4, 0), key(1500, 6, 1));
        DisplayMotion path = DisplayMotion.spline(keys, 1500);

        for (DisplayKeyframe key : keys) {
            DisplayKeyframe hit = closestTo(path.poses(), key.atMillis());
            assertEquals(key.atMillis(), hit.atMillis());
            assertEquals(key.x(), hit.x(), 1e-6);
            assertEquals(key.y(), hit.y(), 1e-6);
        }
        // Between two keys a straight line would sit on the chord; the curve
        // keeps rising past the second key's height on its way there.
        DisplayKeyframe before = closestTo(path.poses(), 400);
        assertTrue(before.y() > 0.8, "curved, not a chord: " + before.y());
        assertTrue(path.poses().size() <= DisplayMotion.maxPoses(1500));
        assertTrue(path.poses().size() > keys.size());
    }

    @Test
    @DisplayName("the Catmull-Rom curve starts and ends on its two middle points")
    void catmullRomEnds() {
        assertEquals(1.0, DisplayMotion.catmullRom(0, 1, 5, 2, 0), 1e-12);
        assertEquals(5.0, DisplayMotion.catmullRom(0, 1, 5, 2, 1), 1e-12);
        assertEquals(1.5, DisplayMotion.catmullRom(0, 1, 2, 3, 0.5), 1e-12, "evenly spaced is a line");
    }

    @Test
    @DisplayName("a pose part of the way to another is where the client draws it")
    void towards() {
        DisplayKeyframe from = new DisplayKeyframe(0, 0, 0, 0, Rotation.NONE, 1, 1, 1);
        DisplayKeyframe to = new DisplayKeyframe(1000, 4, 2, 0,
                Rotation.around(Rotation.Axis.Y, Math.PI / 2), 3, 3, 3);
        DisplayKeyframe half = from.towards(to, 0.5);

        assertEquals(500, half.atMillis());
        assertEquals(2f, half.x(), 1e-6);
        assertEquals(2f, half.scaleY(), 1e-6);
        assertEquals(1.0, Math.abs(dot(half.rotation(),
                Rotation.around(Rotation.Axis.Y, Math.PI / 4))), 1e-5);
    }

    private static DisplayKeyframe key(long at, float x, float y) {
        return new DisplayKeyframe(at, x, y, 0, Rotation.NONE, 1, 1, 1);
    }

    private static DisplayKeyframe closestTo(List<DisplayKeyframe> poses, long at) {
        DisplayKeyframe best = poses.get(0);
        for (DisplayKeyframe pose : poses) {
            if (Math.abs(pose.atMillis() - at) < Math.abs(best.atMillis() - at)) {
                best = pose;
            }
        }
        return best;
    }

    private static double dot(Rotation first, Rotation second) {
        return first.x() * second.x() + first.y() * second.y()
                + first.z() * second.z() + first.w() * second.w();
    }
}
