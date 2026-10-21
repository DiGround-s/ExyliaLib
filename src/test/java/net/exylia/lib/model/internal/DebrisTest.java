package net.exylia.lib.model.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cubes thrown, bounced and brought to rest against a grid of blocks, worked out to the pixel. */
class DebrisTest {

    /** A floor: every block below y = 64 is solid. */
    private static final Debris.Solid FLOOR = (x, y, z) -> y < 64 ? 1 : 0;

    private static final double[] ORIGIN = {0.5, 64, 0.5};
    private static final double[] CENTRE = {0, 0.5, 0};

    private static DisplayKeyframe cube(float x, float y, float z, float size) {
        return new DisplayKeyframe(0, x, y, z, Rotation.NONE, size, size, size);
    }

    private static DisplayMotion fly(DisplayKeyframe from, double[] push, Debris.Solid solid, long seed) {
        double[] extent = {from.scaleX(), from.scaleY(), from.scaleZ()};
        return Debris.fly(from, extent, ORIGIN, push, CENTRE, solid, new Random(seed));
    }

    @Test
    void theSameDiceFlyTheSameWay() {
        DisplayMotion one = fly(cube(0.2f, 0.6f, 0f, 0.25f), new double[3], FLOOR, 7);
        DisplayMotion two = fly(cube(0.2f, 0.6f, 0f, 0.25f), new double[3], FLOOR, 7);
        assertEquals(one.poses(), two.poses());
    }

    @Test
    void itLandsOnTheFloorAndLiesThereNeverBelowIt() {
        for (long seed = 0; seed < 50; seed++) {
            DisplayMotion motion = fly(cube(0.2f, 0.6f, 0.1f, 0.25f), new double[3], FLOOR, seed);
            List<DisplayKeyframe> poses = motion.poses();
            for (DisplayKeyframe pose : poses.subList(0, poses.size() - 1)) {
                assertTrue(pose.y() >= -1e-3, "seed " + seed + ": under the floor at " + pose.atMillis() + " ms");
            }
            DisplayKeyframe resting = poses.get(poses.size() - 2);
            assertEquals(0.0625 + 0.0625, resting.y(), 0.02, "seed " + seed + ": lies on the floor, on its half");
            DisplayKeyframe melted = poses.get(poses.size() - 1);
            assertEquals(0.01f, melted.scaleY(), 1e-6);
            assertTrue(motion.lifeMillis() <= Debris.MAX_FLIGHT_MS + Debris.REST_MS + Debris.MELT_MS + 50);
            assertTrue(poses.size() <= DisplayMotion.maxPoses(motion.lifeMillis()), "few poses: " + poses.size());
        }
    }

    @Test
    void itBouncesLowerEachTime() {
        // Dropped straight down from high up, no throw: watch the peaks between floor touches.
        Debris.Solid floor = FLOOR;
        DisplayMotion motion = fly(cube(0f, 3f, 0f, 0.5f), new double[]{0, -6, 0}, floor, 3);
        List<DisplayKeyframe> poses = motion.poses();
        double lastPeak = Double.MAX_VALUE;
        int peaks = 0;
        for (int index = 1; index < poses.size() - 3; index++) {
            float before = poses.get(index - 1).y();
            float now = poses.get(index).y();
            float after = poses.get(index + 1).y();
            if (now > before && now >= after && now > 0.3) {
                assertTrue(now < lastPeak, "each bounce is lower than the one before");
                lastPeak = now;
                peaks++;
            }
        }
        assertTrue(peaks >= 1, "it bounced at least once");
    }

    @Test
    void aWallStopsItAndTheRestOfItsFlightGoesOn() {
        // A wall one block east of the origin, everything else air over the floor.
        Debris.Solid walled = (x, y, z) -> y < 64 || x >= 2 ? 1 : 0;
        for (long seed = 0; seed < 20; seed++) {
            DisplayMotion motion = fly(cube(0.4f, 0.6f, 0f, 0.25f), new double[]{6, 0, 0}, walled, seed);
            for (DisplayKeyframe pose : motion.poses()) {
                assertTrue(ORIGIN[0] + pose.x() < 2, "seed " + seed + ": inside the wall at " + pose.x());
            }
        }
    }

    @Test
    void aBlowThrowsItAway() {
        double sum = 0;
        for (long seed = 0; seed < 20; seed++) {
            DisplayMotion motion = fly(cube(0f, 0.6f, 0f, 0.25f), new double[]{0, 0, 4}, FLOOR, seed);
            List<DisplayKeyframe> poses = motion.poses();
            sum += poses.get(poses.size() - 1).z();
        }
        assertTrue(sum / 20 > 1, "pushed south, it lands south: " + sum / 20);
    }

    @Test
    void aSlabIsLandedOnAtItsOwnHeight() {
        Debris.Solid slab = (x, y, z) -> y < 64 ? 1 : y == 64 ? 0.5 : 0;
        DisplayMotion motion = fly(cube(0f, 1.5f, 0f, 0.25f), new double[]{0, 0, 0}, slab, 11);
        List<DisplayKeyframe> poses = motion.poses();
        assertEquals(0.5 + 0.125, poses.get(poses.size() - 2).y(), 0.02);
    }
}
