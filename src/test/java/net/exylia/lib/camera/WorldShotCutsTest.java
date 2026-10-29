package net.exylia.lib.camera;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cuts, dips, aimed segments, title cards and overlapping subtitles (1.397.0). */
class WorldShotCutsTest {

    private static final double EPSILON = 1e-6;

    private static Location at(double x, double y, double z, float yaw, float pitch) {
        return new Location(null, x, y, z, yaw, pitch);
    }

    @Test
    @DisplayName("a cut holds the key before, then is there at once, and nothing flows across it")
    void cut() {
        WorldShot shot = WorldShot.builder()
                .key(0, at(0, 70, 0, 0, 0), "smooth")
                .key(2, at(10, 70, 0, 0, 0), "smooth")
                .cut(4, at(500, 90, 500, 180, 20))
                .key(6, at(510, 90, 500, 180, 20), "linear")
                .build();
        assertEquals(10, shot.at(3999)[0], EPSILON);
        assertEquals(500, shot.at(4000)[0], EPSILON);
        assertEquals(505, shot.at(5000)[0], EPSILON);
        // The spline into the key before the cut does not aim at the cut's far landing.
        assertTrue(shot.at(1900)[0] <= 10 + EPSILON);
        assertTrue(shot.cutBetween(3900, 4000));
        assertFalse(shot.cutBetween(4000, 4100));
    }

    @Test
    @DisplayName("a dip is black at its moment and clear outside it")
    void dip() {
        WorldShot shot = WorldShot.builder()
                .key(0, at(0, 70, 0, 0, 0)).key(8, at(10, 70, 0, 0, 0))
                .dip(4, 1)
                .build();
        assertTrue(shot.fades());
        assertEquals(0, shot.fadeAlpha(3000));
        assertEquals(255, shot.fadeAlpha(4000));
        assertEquals(128, shot.fadeAlpha(4250), 1);
        assertEquals(0, shot.fadeAlpha(4600));
    }

    @Test
    @DisplayName("look-at turns the camera onto a point between two moments")
    void lookAt() {
        WorldShot shot = WorldShot.builder()
                .key(0, at(0, 70, 0, 0, 0), "linear").key(4, at(0, 70, 10, 0, 0), "linear")
                .lookAt(0, 2, at(10, 70, 0, 0, 0))
                .build();
        double[] aimed = shot.at(0);
        // East of the camera is yaw -90 in Minecraft's terms.
        assertEquals(-90, aimed[3], EPSILON);
        assertEquals(0, aimed[4], EPSILON);
        assertEquals(0, shot.at(3000)[3], EPSILON);
    }

    @Test
    @DisplayName("overlapping subtitles all show, at most four, and title cards and sizes are kept")
    void overlap() {
        WorldShot.Builder builder = WorldShot.builder().key(0, at(0, 70, 0, 0, 0)).key(1, at(1, 70, 0, 0, 0));
        for (int index = 0; index < 5; index++) {
            builder.subtitle(new WorldShot.Subtitle(index * 100L, 5000, null, Component.text("line " + index),
                    WorldShot.Position.BOTTOM, WorldShot.Align.LEFT, 0, -1, null));
        }
        Component card = Component.text("ACT I");
        WorldShot shot = builder.title(0.5, 2, card).letterbox(0.15).subtitleStyle(new WorldShot.TextStyle(0x33000000, false, 300, 1.5, false, 0.02)).build();
        List<WorldShot.Subtitle> showing = shot.subtitlesAt(450);
        assertEquals(4, showing.size());
        assertEquals(100, showing.get(0).startMillis());
        assertEquals(4, shot.maxConcurrentSubtitles());
        assertSame(card, shot.titleAt(600).text());
        assertNull(shot.titleAt(2600));
        assertEquals(0.15, shot.barSize(), EPSILON);
        assertEquals(1.5, shot.subtitleStyle().scale(), EPSILON);
        assertTrue(shot.letterbox());
        assertFalse(WorldShot.builder().letterbox(0).build().letterbox());
    }
}
