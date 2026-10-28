package net.exylia.lib.camera;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shot through fixed places: the path it solves and the overlay timeline.
 *
 * <p>No server: a {@link Location} with no world is still a position and a
 * heading, which is all the path reads.
 */
class WorldShotTest {

    private static final double EPSILON = 1e-6;

    private static Location at(double x, double y, double z, float yaw, float pitch) {
        return new Location(null, x, y, z, yaw, pitch);
    }

    @Test
    @DisplayName("keys are absolute and timed from the start; the camera holds before the first and after the last")
    void keys() {
        WorldShot shot = WorldShot.builder()
                .key(1, at(0, 70, 0, 0, 10), "linear")
                .key(3, at(20, 80, 0, 90, -10), "linear")
                .build();
        assertEquals(3000, shot.durationMillis());
        assertEquals(0, shot.at(0)[0], EPSILON);
        assertEquals(0, shot.at(1000)[0], EPSILON);
        double[] middle = shot.at(2000);
        assertEquals(10, middle[0], EPSILON);
        assertEquals(75, middle[1], EPSILON);
        assertEquals(45, middle[3], EPSILON);
        assertEquals(0, middle[4], EPSILON);
        assertEquals(20, shot.at(9000)[0], EPSILON);
    }

    @Test
    @DisplayName("a pan across north turns the short way")
    void unwrap() {
        WorldShot shot = WorldShot.builder()
                .key(0, at(0, 70, 0, 170, 0), "linear")
                .key(1, at(0, 70, 0, -170, 0), "linear")
                .build();
        assertEquals(180, shot.at(500)[3], EPSILON);
        assertEquals(190, shot.at(1000)[3], EPSILON);
    }

    @Test
    @DisplayName("smooth runs through a middle key without stopping there")
    void smooth() {
        WorldShot shot = WorldShot.builder()
                .key(0, at(0, 70, 0, 0, 0), "smooth")
                .key(1, at(10, 70, 0, 0, 0), "smooth")
                .key(2, at(20, 70, 0, 0, 0), "smooth")
                .build();
        assertEquals(10, shot.at(1000)[0], EPSILON);
        double before = shot.at(950)[0];
        double after = shot.at(1050)[0];
        // Still moving at about ten blocks a second through the key.
        assertEquals(1.0, after - before, 0.05);
        assertTrue(shot.at(500)[0] > 0 && shot.at(500)[0] < 10);
    }

    @Test
    @DisplayName("keys are put in time order, an unknown ease is refused, and the shot is cut at its ceiling")
    void building() {
        WorldShot shot = WorldShot.builder()
                .key(2, at(20, 70, 0, 0, 0), "linear")
                .key(0, at(0, 70, 0, 0, 0))
                .key(WorldShot.MAX_MILLIS / 1000.0 + 5, at(99, 70, 0, 0, 0))
                .build();
        assertEquals(2, shot.keyCount());
        assertEquals(20, shot.at(2000)[0], EPSILON);
        assertThrows(IllegalArgumentException.class,
                () -> WorldShot.builder().key(0, at(0, 0, 0, 0, 0), "wobbly"));
    }

    @Test
    @DisplayName("the subtitle on screen is the latest line that has started and not run out")
    void subtitles() {
        Component first = Component.text("first");
        Component reply = Component.text("reply");
        WorldShot shot = WorldShot.builder()
                .key(0, at(0, 70, 0, 0, 0))
                .key(2, at(5, 70, 0, 0, 0))
                .subtitle(0.5, 2, first)
                .subtitle(1.5, 3, Component.text("Guide"), reply)
                .build();
        // The track outlasts the keys, and the shot lasts as long as it.
        assertEquals(4500, shot.durationMillis());
        assertNull(shot.subtitleAt(100));
        assertSame(first, shot.subtitleAt(600).text());
        assertSame(reply, shot.subtitleAt(1600).text());
        assertSame(reply, shot.subtitleAt(4000).text());
        assertNull(shot.subtitleAt(4500));
        assertTrue(shot.hasOverlay());
    }

    @Test
    @DisplayName("the fade runs from black to clear at the start and back at the end")
    void fade() {
        WorldShot shot = WorldShot.builder()
                .key(0, at(0, 70, 0, 0, 0))
                .key(10, at(5, 70, 0, 0, 0))
                .fade(1, 2)
                .build();
        assertEquals(255, shot.fadeAlpha(0));
        assertEquals(128, shot.fadeAlpha(500));
        assertEquals(0, shot.fadeAlpha(1000));
        assertEquals(0, shot.fadeAlpha(8000));
        assertEquals(128, shot.fadeAlpha(9000));
        assertEquals(255, shot.fadeAlpha(10000));
        assertEquals(0, WorldShot.builder().key(0, at(0, 0, 0, 0, 0)).key(1, at(1, 0, 0, 0, 0))
                .build().fadeAlpha(0));
    }
}
