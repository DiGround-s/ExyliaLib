package net.exylia.lib.camera.internal;

import net.exylia.lib.camera.CameraShot;
import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a shot actually puts the camera.
 *
 * <p>This is the arithmetic every shot is built on, and getting a sign wrong
 * here is not a subtle bug: it films the back of the player's head when the file
 * asked for their face, and every shot anybody writes afterwards is written
 * around the mistake.
 *
 * <p>No world, so no block clearance: the path leaves the distance alone when
 * there is nothing to ray-trace through, which is what makes the geometry
 * checkable without a server.
 */
class CameraPathTest {

    private static final double EPSILON = 1e-6;

    /** Facing south, which is where a yaw of zero points in Minecraft. */
    private static Location facingSouth() {
        return new Location(null, 0, 64, 0, 0f, 0f);
    }

    private static List<CameraPath.Frame> path(String text) {
        return CameraPath.of(CameraShot.parse(text, problem -> {
            throw new AssertionError(problem);
        }), facingSouth());
    }

    @Test
    @DisplayName("yaw zero sits behind the subject and looks the way they face")
    void behind() {
        CameraPath.Frame at = path("0 distance=4 yaw=0 pitch=0 height=1.5 | 0.2 distance=4").get(0);

        // South is +Z, so behind them is -Z.
        assertEquals(0.0, at.x(), EPSILON);
        assertEquals(65.5, at.y(), EPSILON);
        assertEquals(-4.0, at.z(), EPSILON);
        assertEquals(0f, at.yaw(), EPSILON);
        assertEquals(0f, at.pitch(), EPSILON);
    }

    @Test
    @DisplayName("yaw of a half turn sits in front of them and looks back")
    void inFront() {
        CameraPath.Frame at = path("0 distance=4 yaw=180 pitch=0 height=1.5 | 0.2 distance=4").get(0);

        assertEquals(4.0, at.z(), EPSILON);
        assertEquals(180f, Math.abs(at.yaw()), 1e-4);
    }

    @Test
    @DisplayName("yaw of a quarter turn sits off their left")
    void offTheLeft() {
        CameraPath.Frame at = path("0 distance=4 yaw=90 pitch=0 height=1.5 | 0.2 distance=4").get(0);

        // Facing south, a player's left hand points east, which is +X.
        assertEquals(4.0, at.x(), EPSILON);
        assertEquals(0.0, at.z(), EPSILON);
        // Looking back west at them, which is a yaw of 90 in Minecraft.
        assertEquals(90f, at.yaw(), 1e-4);
    }

    @Test
    @DisplayName("a positive pitch puts the camera above them, looking down")
    void above() {
        CameraPath.Frame at = path("0 distance=4 yaw=0 pitch=30 height=0 | 0.2 distance=4").get(0);

        assertEquals(66.0, at.y(), EPSILON);
        assertEquals(30f, at.pitch(), 1e-4);
        assertTrue(at.pitch() > 0, "a camera above the subject looks down at it");
    }

    @Test
    @DisplayName("one position every sample, and one more for the moment it ends on")
    void sampleCount() {
        assertEquals(11, path("0 behind | 1 wide").size());
        assertEquals(6, path("0 behind | 0.5 wide").size());
    }

    @Test
    @DisplayName("an orbit never turns further in one step than the client can follow")
    void orbitStepsAreFollowable() {
        List<CameraPath.Frame> frames = path("0 close | 3.2 yaw=~360 ease=linear");

        for (int index = 1; index < frames.size(); index++) {
            double turned = Math.abs(wrap(frames.get(index).yaw() - frames.get(index - 1).yaw()));
            assertTrue(turned < 180, "step " + index + " turns " + turned + " degrees");
        }
    }

    /** The same shortest way round the client takes. */
    private static double wrap(double degrees) {
        double wrapped = (degrees + 180) % 360;
        return (wrapped < 0 ? wrapped + 360 : wrapped) - 180;
    }
}
