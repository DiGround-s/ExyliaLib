package net.exylia.lib.stage;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rules page brought up ahead fits its half of the backing, however long its lines. */
class StageGuideFitTest {

    private static List<StageGuide.Row> lines(String... text) {
        return StageGuide.rows(List.of(text).stream().map(Component::text).map(Component.class::cast).toList());
    }

    @Test
    @DisplayName("a long line shrinks the page until it fits across")
    void longLineFitsAcross() {
        List<StageGuide.Row> rows = lines("short", "the more of your numbers come up the more it pays");
        double scale = StageGuide.fit(null, rows, 1.5, 100);
        String longest = "the more of your numbers come up the more it pays";
        // Plain glyphs are a pixel (0.025 blocks at scale 1) narrower than the bold ones StageLook measures.
        double wide = (StageLook.width(longest) - longest.length() * 0.025) * scale;

        assertEquals(1.5, wide, 1e-9, "the widest line spans the room exactly");
        assertTrue(StageGuide.fit(null, rows, 3.0, 100) > scale, "more room, larger text");
    }

    @Test
    @DisplayName("many rows shrink the page until title and rows fit down")
    void manyRowsFitDown() {
        List<StageGuide.Row> rows = lines("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        double scale = StageGuide.fit(Component.text("T"), rows, 100, 1.0);
        double up = Stage.TEXT_HEIGHT * scale * (10 / 2.0 + 0.5 + 1.7);

        assertEquals(0.5, up, 1e-9, "the top half reaches exactly the top edge");
    }

    @Test
    @DisplayName("no rows leave the other half's size alone")
    void noRows() {
        assertEquals(Double.MAX_VALUE, StageGuide.fit(Component.text("T"), List.of(), 1, 1));
    }
}
