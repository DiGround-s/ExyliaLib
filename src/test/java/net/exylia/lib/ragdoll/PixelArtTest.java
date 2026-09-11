package net.exylia.lib.ragdoll;

import net.exylia.lib.util.sequence.internal.ShapePoints;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pictures and words drawn in blocks: that they come out the right size, shape and way round. */
class PixelArtTest {

    @Test
    @DisplayName("a heart is its cells, centred across and standing on the anchor")
    void heartIsCentred() {
        List<Vector> heart = ShapePoints.of("pixels", "art:heart;pixel:0.2");
        assertEquals(46, heart.size());
        double lowest = heart.stream().mapToDouble(Vector::getY).min().orElseThrow();
        double left = heart.stream().mapToDouble(Vector::getX).min().orElseThrow();
        double right = heart.stream().mapToDouble(Vector::getX).max().orElseThrow();
        assertEquals(0, lowest, 1e-9, "the bottom row stands on the anchor");
        assertEquals(-left, right, 1e-9, "a heart is symmetric about its middle");
        assertEquals(0.8, right, 1e-9, "nine cells of a fifth are 1.6 blocks wide");
    }

    @Test
    @DisplayName("pick draws one colour of a picture and nothing else")
    void pickSelectsColours() {
        int all = ShapePoints.of("pixels", "art:heart").size();
        int shine = ShapePoints.of("pixels", "art:heart;pick:w").size();
        int body = ShapePoints.of("pixels", "art:heart;pick:#").size();
        assertEquals(3, shine);
        assertEquals(all, shine + body);
    }

    @Test
    @DisplayName("a word reads left to right along east")
    void wordsReadForwards() {
        List<Vector> ez = ShapePoints.of("pixels", "word:EZ;pixel:1");
        // E is five wide, then a gap, then Z: eleven columns, centred.
        double right = ez.stream().mapToDouble(Vector::getX).max().orElseThrow();
        assertEquals(5, right, 1e-9);
        // The E's upright is on the left: the leftmost column is full height.
        long upright = ez.stream().filter(point -> point.getX() == -5).count();
        assertEquals(5, upright, "the E is not at the start of the word");
        assertTrue(ez.stream().noneMatch(point -> point.getX() == 0), "the gap between letters was drawn");
    }

    @Test
    @DisplayName("an unknown picture draws the heart rather than nothing")
    void unknownArtFallsBack() {
        assertEquals(ShapePoints.of("pixels", "art:heart").size(),
                ShapePoints.of("pixels", "art:nonsense").size());
    }
}
