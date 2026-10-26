package net.exylia.lib.stage;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagePlaneTest {

    /** A 3×3 board two blocks south of the origin, facing a seat that looks south (yaw 0). */
    private static StagePlane board() {
        return new StagePlane(null, 0, 0, 2, 0f, 3, 3, 0.5, 0.4);
    }

    @Test
    @DisplayName("a ray straight ahead lands on the middle cell")
    void middle() {
        assertEquals(4, board().pick(0, 0, 0, 0, 0, 1));
    }

    @Test
    @DisplayName("cells are numbered from the top left as the seat sees them; its right is -X when looking south")
    void corners() {
        StagePlane plane = board();
        // Looking south, the seat's right hand is west (-X): the top-right cell is up and to -X.
        assertEquals(2, plane.pick(0, 0, 0, -0.25, 0.25, 1));
        assertEquals(0, plane.pick(0, 0, 0, 0.25, 0.25, 1));
        assertEquals(6, plane.pick(0, 0, 0, 0.25, -0.25, 1));
        assertEquals(8, plane.pick(0, 0, 0, -0.25, -0.25, 1));
    }

    @Test
    @DisplayName("the gap between two cells, beside the board and a ray pointing away all miss")
    void misses() {
        StagePlane plane = board();
        assertEquals(-1, plane.pick(0, 0, 0, 0.125, 0, 1), "half a pitch over is the gap");
        assertEquals(-1, plane.pick(0, 0, 0, 1, 0, 1), "beside the board");
        assertEquals(-1, plane.pick(0, 0, 0, 0, 0, -1), "looking away");
        assertNull(plane.hit(0, 0, 0, 1, 0, 0), "parallel to the plane");
        assertNull(plane.hit(0, 0, 3, 0, 0, 1), "the board is behind the eyes");
    }

    @Test
    @DisplayName("a hit is in the board's own terms, and a cell's centre is picked back as that cell")
    void hitAndCellAgree() {
        StagePlane plane = board();
        StagePlane.Hit hit = plane.hit(0, 0, 0, -0.25, 0.1, 1);
        assertNotNull(hit);
        assertEquals(0.5, hit.across(), 1e-9);
        assertEquals(0.2, hit.up(), 1e-9);
        assertTrue(hit.inside(0.5, 0.2, 0.1, 0.1));
        assertFalse(hit.inside(0, 0, 0.5, 0.5));
        for (int cell = 0; cell < plane.cells(); cell++) {
            Location centre = plane.cell(cell, 0);
            assertEquals(cell, plane.pick(0, 0, 0, centre.getX(), centre.getY(), centre.getZ()), "cell " + cell);
        }
    }

    @Test
    @DisplayName("a column is picked anywhere from the top of the board to its bottom")
    void columns() {
        StagePlane plane = new StagePlane(null, 0, 0, 2, 0f, 7, 6, 0.3, 0.28);
        assertEquals(3, plane.column(new StagePlane.Hit(0, 0.85)));
        assertEquals(0, plane.column(new StagePlane.Hit(-1.0, 0)));
        assertEquals(6, plane.column(new StagePlane.Hit(1.0, -0.8)));
        assertEquals(-1, plane.column(new StagePlane.Hit(0, 0.95)), "over the top row");
        assertEquals(-1, plane.column(new StagePlane.Hit(1.2, 0)), "beside the board");
        assertEquals(-1, plane.column(null));
    }

    @Test
    @DisplayName("a board in front of a seat is picked from that seat's eyes, whichever way it faces")
    void inFrontOfAnySeat() {
        for (float yaw : new float[]{0f, 90f, -135f, 180f}) {
            Location seat = new Location(null, 10, 64, -5, yaw, 0);
            StagePlane plane = StagePlane.inFrontOf(seat, 1.62, 2.5, 0, 3, 3, 0.5, 0.4);
            Location eyes = seat.clone().add(0, 1.62, 0);
            assertEquals(4, plane.pick(eyes), "yaw " + yaw);
        }
    }

    @Test
    @DisplayName("the zoom brings a board nearer only as far as it still fits the view")
    void zoom() {
        assertEquals(1, StagePlane.zoom(2.5, 0, -0.5, 0.5, -0.5, 0.5, 1, 110, 80, 1.5), 1e-9, "no zoom asked");
        assertEquals(2, StagePlane.zoom(3, 0, -0.2, 0.2, -0.2, 0.2, 2, 110, 80, 1.0), 1e-9, "a small board takes it all");
        double capped = StagePlane.zoom(2.5, 0, -1, 1, -0.8, 0.8, 4, 110, 80, 0.5);
        assertTrue(capped > 1 && capped < 4, "a wide board stops growing: " + capped);
        double tall = 2 * Math.toDegrees(Math.atan(0.8 * capped / 2.5));
        assertEquals(80, tall, 0.1, "it stops where it spans the tallest allowed, the tighter of the two limits here");
        assertEquals(2.5 / 1.5, StagePlane.zoom(2.5, 0, -0.1, 0.1, -0.1, 0.1, 4, 110, 80, 1.5), 1e-9,
                "never nearer than the nearest");
    }

    @Test
    @DisplayName("fit moves an untouched board nearer once, and leaves a drawn-on one alone")
    void fitOnlyBeforeUse() {
        Location seat = new Location(null, 0, 0, 0, 0f, 0);
        StagePlane plane = StagePlane.inFrontOf(seat, 0, 3, 0, 1, 1, 1, 1);
        assertEquals(2, plane.fit(-0.2, 0.2, -0.2, 0.2, 2, 110, 80, 1), 1e-9);
        assertEquals(1.5, plane.point(0, 0, 0).getZ(), 1e-9);
        assertEquals(1, plane.fit(-0.2, 0.2, -0.2, 0.2, 2, 110, 80, 1), 1e-9, "a second fit does nothing");

        StagePlane used = StagePlane.inFrontOf(seat, 0, 3, 0, 1, 1, 1, 1);
        used.point(0, 0, 0);
        assertEquals(1, used.fit(-0.2, 0.2, -0.2, 0.2, 2, 110, 80, 1), 1e-9);
        assertEquals(3, used.point(0, 0, 0).getZ(), 1e-9);
    }
}
