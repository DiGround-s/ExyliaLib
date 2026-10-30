package net.exylia.lib.spatial.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.stage.StagePlane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTest {

    /** A player at the origin looking south, the board 3.4 ahead of the eyes. */
    private static StagePlane board() {
        return new StagePlane(null, 0, 1.62, 3.4, 0f, 1, 1, 1, 1);
    }

    @Test
    void aTurnedPanelFacesTheEyes() {
        StagePlane left = Geometry.panel(null, board(), -2.05, 0.45, 0.45, 24);
        var centre = left.point(0, 0, 0);
        assertEquals(2.05, centre.getX(), 1e-9, "across -2.05 is the viewer's left: east when looking south");
        assertEquals(3.4 - 0.45, centre.getZ(), 1e-9);
        double yaw = Math.toRadians(left.yaw());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double length = Math.hypot(centre.getX(), centre.getZ());
        double dot = fx * centre.getX() / length + fz * centre.getZ() / length;
        assertTrue(dot > Math.cos(Math.toRadians(20)), "turned towards the eyes, off by " + Math.toDegrees(Math.acos(dot)));
        StagePlane right = Geometry.panel(null, board(), 2.05, 0.45, 0.45, -24);
        assertEquals(-left.yaw(), right.yaw(), 1e-6, "mirrored");
    }

    @Test
    void raysHitATurnedPanelOnlyInsideIt() {
        StagePlane panel = Geometry.panel(null, board(), -2.05, 0.45, 0.45, 24);
        var centre = panel.point(0, 0, 0);
        double dx = centre.getX();
        double dy = centre.getY() - 1.62;
        double dz = centre.getZ();
        StagePlane.Hit hit = panel.hit(0, 1.62, 0, dx, dy, dz);
        assertNotNull(hit);
        assertEquals(0, hit.across(), 1e-6);
        assertEquals(0, hit.up(), 1e-6);
        assertTrue(hit.inside(0, 0, 1.35, 1.55));
        var edge = panel.point(0.8, 0, 0);
        StagePlane.Hit beside = panel.hit(0, 1.62, 0, edge.getX(), edge.getY() - 1.62, edge.getZ());
        assertNotNull(beside);
        assertEquals(0.8, beside.across(), 1e-6);
        assertTrue(!beside.inside(0, 0, 1.35, 1.55), "past its right edge");
        assertNull(panel.hit(0, 1.62, 0, 0, 0, -1), "looking away");
    }

    @Test
    void theCarouselIsSymmetricWithAGapAndACurve() {
        int slots = 6;
        double spacing = 0.62;
        double gap = 1.3;
        double curve = 0.15;
        for (int i = 0; i < slots; i++) {
            double[] a = Geometry.carousel(i, slots, spacing, gap, curve);
            double[] b = Geometry.carousel(slots - 1 - i, slots, spacing, gap, curve);
            assertEquals(-a[0], b[0], 1e-9, "mirrored");
            assertEquals(a[1], b[1], 1e-9);
        }
        assertEquals(-(gap / 2 + spacing / 2), Geometry.carousel(2, slots, spacing, gap, curve)[0], 1e-9, "inner left");
        assertEquals(gap / 2 + spacing / 2, Geometry.carousel(3, slots, spacing, gap, curve)[0], 1e-9, "inner right");
        assertEquals(curve, Geometry.carousel(0, slots, spacing, gap, curve)[1], 1e-9, "the outermost come forward by the curve");
        assertTrue(Geometry.carousel(2, slots, spacing, gap, curve)[1] < curve / 2);
        assertEquals(Geometry.outermost(slots, spacing, gap), -Geometry.carousel(0, slots, spacing, gap, curve)[0], 1e-9);
        // No gap: evenly spread round the middle.
        assertEquals(-0.5, Geometry.carousel(0, 3, 0.5, 0, 0)[0], 1e-9);
        assertEquals(0, Geometry.carousel(1, 3, 0.5, 0, 0)[0], 1e-9);
    }

    @Test
    void listsAreCutIntoPagesThatWrap() {
        assertEquals(1, Geometry.pages(0, 6));
        assertEquals(1, Geometry.pages(6, 6));
        assertEquals(3, Geometry.pages(13, 6));
        assertEquals(2, Geometry.wrap(-1, 3));
        assertEquals(0, Geometry.wrap(3, 3));
        List<Integer> all = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertEquals(List.of(12), Geometry.page(all, 2, 6));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), Geometry.page(all, 3, 6), "past the last page wraps to the first");
        assertEquals(List.of(), Geometry.page(List.of(), 0, 6));
    }

    @Test
    void onlyChangedSlotsAreRedrawn() {
        assertEquals(List.of(1), Geometry.changed(List.of("a", "b", "c"), List.of("a", "x", "c"), 4));
        assertEquals(List.of(2), Geometry.changed(List.of("a", "b", "c"), List.of("a", "b"), 4));
        assertEquals(List.of(1, 2), Geometry.changed(List.of("a"), List.of("a", "b", "c"), 4));
        assertEquals(List.of(), Geometry.changed(List.of("a", "b"), List.of("a", "b"), 4));
    }

    @Test
    void glassIsSizedAndCentredOnItsOrigin() {
        DisplayKeyframe unit = Geometry.glass(0, Rotation.NONE, Geometry.GLASS_WIDTH, Geometry.GLASS_HEIGHT, 1, 0, 0, 0);
        assertEquals(1f, unit.scaleX(), 1e-6);
        assertEquals(1f, unit.scaleY(), 1e-6);
        assertEquals(-Geometry.GLASS_HEIGHT / 2, unit.y(), 1e-6, "its foot moved down by half its height");
        // Lying flat, the half-height moves along the ground instead.
        DisplayKeyframe flat = Geometry.glass(0, Rotation.around(Rotation.Axis.X, -Math.PI / 2), 0.4, 0.4, 1, 0, 0, 0);
        assertEquals(0, flat.y(), 1e-6);
        assertEquals(0.2, Math.abs(flat.z()), 1e-6);
    }

    @Test
    void cellsAndContentStayInsideThePanel() {
        double[] area = Geometry.content(1.55, true, true);
        assertTrue(area[0] + area[1] / 2 <= 1.55 / 2 - 0.3 + 1e-9);
        assertTrue(area[0] - area[1] / 2 >= -1.55 / 2 + 0.3 - 1e-9);
        double[] topLeft = Geometry.cell(0, 3, 4, 1.2, 0.8);
        double[] bottomRight = Geometry.cell(11, 3, 4, 1.2, 0.8);
        assertEquals(-0.4, topLeft[0], 1e-9);
        assertEquals(0.3, topLeft[1], 1e-9);
        assertEquals(0.4, bottomRight[0], 1e-9);
        assertEquals(-0.3, bottomRight[1], 1e-9);
    }

    @Test
    void coloursMix() {
        assertEquals(0x000000, Geometry.mix(0x000000, 0xFFFFFF, 0));
        assertEquals(0xFFFFFF, Geometry.mix(0x000000, 0xFFFFFF, 1));
        assertEquals(0xD0123456, Geometry.argb(0xD0, 0x123456));
    }
}
