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
    void elementsPopInTheirOwnMiddle() {
        double[] at = {3, 0};
        // Drawn at nothing, the bottom centre stands half its height up: the middle stays put.
        assertEquals(3, HudGeometry.popped(at, 2, 0)[0], 1e-9);
        assertEquals(1, HudGeometry.popped(at, 2, 0)[1], 1e-9);
        assertEquals(0, HudGeometry.popped(at, 2, 1)[1], 1e-9);
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

    @Test
    void aFittedViewMovesCornersOntoItsOwnEdges() {
        double zoom = net.exylia.lib.screen.internal.ScreenPreferences.fovScale(90);
        assertEquals(Math.tan(Math.toRadians(45)) / Math.tan(Math.toRadians(35)), zoom, 1e-9);
        assertEquals(1, net.exylia.lib.screen.internal.ScreenPreferences.fovScale(70), 1e-9);
        double[] corner = HudGeometry.place(HudAnchor.TOP_RIGHT, 0, 0, 0, zoom, 21.0 / 9);
        double[] plain = HudGeometry.place(HudAnchor.TOP_RIGHT, 0, 0, 0);
        assertEquals(plain[0] * zoom * (21.0 / 9) / HudGeometry.ASPECT, corner[0], 1e-9);
        assertEquals(plain[1] * zoom, corner[1], 1e-9);
    }

    @Test
    void sprintingWidensOnlyAsMuchAsTheSliderLets() {
        assertEquals(1.15, HudGeometry.fovModifier(0.13, 0.2f, false, 1), 1e-6);
        assertEquals(1.075, HudGeometry.fovModifier(0.13, 0.2f, false, 0.5), 1e-6);
        assertEquals(1, HudGeometry.fovModifier(0.13, 0.2f, false, 0), 1e-6);
        assertEquals(1, HudGeometry.fovModifier(0.1, 0.2f, false, 1), 1e-6);
        assertEquals(1, HudGeometry.widen(1, 70), 1e-9);
        assertTrue(HudGeometry.widen(1.15, 70) > 1.15);
    }

    @Test
    void samplesCoverTheRectangleNoFurtherApartThanTheStep() {
        double[][] points = HudGeometry.samples(-10, 0, 10, 1, 1.5);
        assertEquals(15 * 2, points.length);
        for (double[] point : points) {
            assertTrue(point[0] >= -10 && point[0] <= 10 && point[1] >= 0 && point[1] <= 1);
        }
        assertEquals(4, HudGeometry.samples(0, 0, 0, 0, 1.5).length);
    }

    @Test
    void anElementStandsJustInFrontOfWhatCoversIt() {
        assertEquals(1, HudGeometry.room(Double.POSITIVE_INFINITY, 0.4, 1.5, 16));
        assertEquals(1, HudGeometry.room(16.4, 0.4, 1.5, 16), 1e-9);
        assertEquals(0.25, HudGeometry.room(4.4, 0.4, 1.5, 16), 1e-9);
        assertEquals(-1, HudGeometry.room(1.8, 0.4, 1.5, 16));
    }
}
