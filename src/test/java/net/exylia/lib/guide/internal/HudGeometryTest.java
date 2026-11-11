package net.exylia.lib.guide.internal;

import net.exylia.lib.hud.HudAnchor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudGeometryTest {

    @Test
    void cornersKeepTheirEdgeInsideTheView() {
        double[] left = HudGeometry.place(HudAnchor.TOP_LEFT, 0, 2, 1);
        assertEquals(-HudGeometry.HALF_WIDTH * 0.97 + 1, left[0], 1e-9);
        assertTrue(left[1] + 1 <= HudGeometry.HALF_HEIGHT, "its top stays under the top edge");
        double[] right = HudGeometry.place(HudAnchor.BOTTOM_RIGHT, 0, 2, 1);
        assertEquals(HudGeometry.HALF_WIDTH * 0.97 - 1, right[0], 1e-9);
        assertTrue(right[1] > -HudGeometry.HALF_HEIGHT, "its bottom stays over the bottom edge");
    }

    @Test
    void stacksGrowAwayFromTheirEdge() {
        assertTrue(HudGeometry.place(HudAnchor.TOP_LEFT, 3, 2, 1)[1] < HudGeometry.place(HudAnchor.TOP_LEFT, 0, 2, 1)[1]);
        assertTrue(HudGeometry.place(HudAnchor.ABOVE_HOTBAR, 3, 2, 1)[1]
                > HudGeometry.place(HudAnchor.ABOVE_HOTBAR, 0, 2, 1)[1]);
    }

    @Test
    void sidesSlideOutOffTheirOwnEdge() {
        double[] at = {0, 0};
        assertTrue(HudGeometry.aside(HudAnchor.LEFT, at, 2, 1)[0] < 0);
        assertTrue(HudGeometry.aside(HudAnchor.RIGHT, at, 2, 1)[0] > 0);
    }

    @Test
    void theSeatIsTakenBackOffLevelAndLookingDown() {
        float[] level = HudGeometry.seated(0, 0, 0, 0.2);
        assertEquals(-0.2, level[1], 1e-6);
        assertEquals(-GuidePackets.VIEW_DISTANCE, level[2], 1e-6);
        float[] down = HudGeometry.seated(0, 0, 90, 0.2);
        assertEquals(0, down[1], 1e-6);
        assertEquals(-GuidePackets.VIEW_DISTANCE - 0.2, down[2], 1e-6);
    }

    @Test
    void anchorsParseAsFilesWriteThem() {
        assertEquals(HudAnchor.TOP_LEFT, HudAnchor.parse("top-left"));
        assertEquals(HudAnchor.CENTER, HudAnchor.parse("centre"));
        assertEquals(null, HudAnchor.parse("nowhere"));
    }

}
