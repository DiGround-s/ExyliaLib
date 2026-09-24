package net.exylia.lib.display;

import net.exylia.lib.FakeServer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a telegraph's plates lie and how its fill moves, as numbers.
 */
class TelegraphsTest {

    private World world;
    private Location centre;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("telegraphs");
        centre = new Location(world, 10, 64, -3);
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private static DisplayKeyframe first(Vfx.Shown shown) {
        return shown.motion().poses().get(0);
    }

    private static DisplayKeyframe last(Vfx.Shown shown) {
        List<DisplayKeyframe> poses = shown.motion().poses();
        return poses.get(poses.size() - 1);
    }

    /** Which way a plate's long side runs, flattened. */
    private static float[] longSide(DisplayKeyframe pose) {
        return pose.rotation().apply(new float[]{1f, 0f, 0f});
    }

    @Test
    @DisplayName("a circle's outline lies on the radius, tangent to it, just above the ground")
    void circleOutline() {
        Vfx vfx = Telegraphs.circle(Vfx.at(centre), 0, centre, 5, 900, 0xFF9500);
        List<Vfx.Shown> shown = vfx.shown();

        assertEquals(80, shown.size(), "forty outline plates and forty fill plates");
        for (int index = 0; index < shown.size(); index += 2) {
            DisplayKeyframe pose = last(shown.get(index));
            assertEquals(Telegraphs.LIFT, pose.y(), 1e-6);
            assertEquals(5.0, Math.hypot(pose.x(), pose.z()), 1e-4);
            float[] side = longSide(pose);
            double radial = (side[0] * pose.x() + side[2] * pose.z()) / 5.0;
            assertEquals(0.0, radial, 1e-4, "plate " + index + " is not tangent");
            assertEquals(900, shown.get(index).motion().lifeMillis());
            assertEquals(centre, shown.get(index).where());
        }
    }

    @Test
    @DisplayName("a circle fills from the middle to the edge at a steady rate over the wind-up")
    void circleFillGrows() {
        List<Vfx.Shown> shown = Telegraphs.circle(Vfx.at(centre), 0, centre, 5, 900, 0xFF9500).shown();
        Vfx.Shown fill = shown.get(1);

        assertEquals(0.0, Math.hypot(first(fill).x(), first(fill).z()), 1e-6);
        assertEquals(5.0, Math.hypot(last(fill).x(), last(fill).z()), 1e-4);
        assertEquals(900, last(fill).atMillis());
        assertTrue(first(fill).scaleX() < last(fill).scaleX(), "it grows along the circle");
        assertEquals(2, fill.motion().poses().size(), "linear: the fill is the timer");
    }

    @Test
    @DisplayName("a ring closes from the edge to the middle")
    void ringFillCloses() {
        Vfx.Shown fill = Telegraphs.ring(Vfx.at(centre), 0, centre, 4, 600, 0x59A4FF).shown().get(1);

        assertEquals(4.0, Math.hypot(first(fill).x(), first(fill).z()), 1e-4);
        assertEquals(0.0, Math.hypot(last(fill).x(), last(fill).z()), 1e-6);
    }

    @Test
    @DisplayName("a lower level of detail halves the plates")
    void lodHalves() {
        int full = Telegraphs.circle(Vfx.at(centre), 0, centre, 5, 900, 0).shown().size();
        int low = Telegraphs.circle(Vfx.at(centre).lod(true), 0, centre, 5, 900, 0).shown().size();

        assertEquals(full / 2, low);
    }

    @Test
    @DisplayName("a cone points where its yaw says and opens as wide as asked")
    void coneFacesItsYaw() {
        // Yaw 0 faces south, which is +z.
        List<Vfx.Shown> shown = Telegraphs.cone(Vfx.at(centre), 0, centre, 0f, 6, 60, 700, 0)
                .shown();

        int arcPlates = shown.size() - 2;
        for (int index = 0; index < arcPlates; index += 2) {
            DisplayKeyframe pose = last(shown.get(index));
            double angle = Math.toDegrees(Math.atan2(pose.x(), pose.z()));
            assertTrue(Math.abs(angle) <= 30.0 + 1e-6, "arc plate at " + angle + " degrees");
            assertEquals(6.0, Math.hypot(pose.x(), pose.z()), 1e-4);
        }
        for (Vfx.Shown edge : shown.subList(arcPlates, shown.size())) {
            DisplayKeyframe pose = last(edge);
            assertEquals(3.0, Math.hypot(pose.x(), pose.z()), 1e-4, "an edge is centred halfway out");
            assertEquals(6.0, pose.scaleX(), 1e-4, "and is as long as the cone");
        }
    }

    @Test
    @DisplayName("a line's fill runs from its start to its end")
    void lineFillRuns() {
        Location to = centre.clone().add(10, 2, 0);
        List<Vfx.Shown> shown = Telegraphs.line(Vfx.at(centre), 0, centre, to, 1.6, 700, 0)
                .shown();

        assertEquals(5, shown.size(), "two sides, two ends and the fill");
        assertEquals(0.8, Math.abs(last(shown.get(0)).z()), 1e-4, "a side is half the width out");
        Vfx.Shown fill = shown.get(4);
        assertEquals(5.0, last(fill).x(), 1e-4, "its middle ends halfway along");
        assertEquals(10.0, last(fill).scaleX(), 1e-4, "and it ends as long as the line");
        assertEquals(Telegraphs.LIFT, last(fill).y(), 1e-6, "flat, whatever the far end's height");
    }

    @Test
    @DisplayName("a cross is two plates growing to full length")
    void crossGrows() {
        List<Vfx.Shown> shown = Telegraphs.cross(Vfx.at(centre), 0, centre, 3, 900, 0).shown();

        assertEquals(2, shown.size());
        assertEquals(3.0, last(shown.get(0)).scaleX(), 1e-4);
    }

    @Test
    @DisplayName("the glass is the dye closest to the tint")
    void glassFollowsTheTint() {
        assertEquals(Material.ORANGE_STAINED_GLASS, Telegraphs.glass(0xFF9500));
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS, Telegraphs.glass(0x59A4FF));
    }
}
