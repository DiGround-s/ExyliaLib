package net.exylia.lib.packet;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeTest {

    @Test
    @DisplayName("a square at yaw 0 holds its own column and nothing past an edge")
    void square() {
        Volume box = new Volume(0, 0, 0f, 2, 64, 70);
        assertTrue(box.holds(0, 65, 0));
        assertTrue(box.holds(2, 64, -2));
        assertFalse(box.holds(2.01, 65, 0));
        assertFalse(box.holds(0, 63.9, 0));
        assertFalse(box.holds(0, 70.1, 0));
    }

    @Test
    @DisplayName("turned 45 degrees, the corner of the unturned square is outside and a point along the diagonal axis inside")
    void turned() {
        Volume box = new Volume(0, 0, 45f, 2, 0, 5);
        assertFalse(box.holdsColumn(2, 2));
        assertTrue(box.holdsColumn(2.5, 0));
        assertFalse(box.holdsColumn(2.9, 0));
    }

    @Test
    @DisplayName("a box touches when it reaches in from outside, and not when it is above or beside")
    void touches() {
        Volume box = new Volume(0.5, 0.5, 0f, 2, 64, 70);
        // A block whose centre is just past the edge still reaches in.
        assertTrue(box.touches(2.5, 64, 0, 3.5, 65, 1));
        assertFalse(box.touches(4, 64, 0, 5, 65, 1));
        // Under the floor, its top at the floor's height: not inside.
        assertFalse(box.touches(0, 63, 0, 1, 64, 1));
        // Over the ceiling.
        assertFalse(box.touches(0, 70, 0, 1, 71, 1));
        // A tall entity standing on the floor.
        assertTrue(box.touches(0.2, 64, 0.2, 0.8, 65.8, 0.8));
    }

    @Test
    @DisplayName("around() puts the bottom under and the top over the centre")
    void around() {
        Volume box = Volume.around(new Location(null, 10, 64.5, -3, 90f, 0), 4.5, 1.5, 5.12);
        assertEquals(63.0, box.bottom(), 1e-9);
        assertEquals(69.62, box.top(), 1e-9);
        assertEquals(90f, box.yaw());
    }
}
