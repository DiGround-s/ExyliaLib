package net.exylia.lib.model.internal;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A model's own boxes: what a hit can reach, and what a projectile flies through. */
class BoxesTest {

    private final BoundingBox golem = Boxes.box(0, 64, 0, 3, 4);

    @Test
    @DisplayName("a box stands on its bottom centre")
    void box() {
        assertEquals(-1.5, golem.getMinX());
        assertEquals(1.5, golem.getMaxZ());
        assertEquals(64, golem.getMinY());
        assertEquals(68, golem.getMaxY());
    }

    @Test
    @DisplayName("a hit reaches a box within the player's range of its nearest side, not its middle")
    void reach() {
        assertTrue(Boxes.reaches(golem, new Vector(4.5, 65.6, 0), 3), "three blocks from its side");
        assertFalse(Boxes.reaches(golem, new Vector(6, 65.6, 0), 3), "four and a half away");
        assertTrue(Boxes.reaches(golem, new Vector(6, 65.6, 0), 4.5), "a longer reach");
        assertEquals(0, Boxes.distance(golem, 0, 65, 0), "inside it");
        assertEquals(2, Boxes.distance(golem, 0, 70, 0), 1e-9, "above it");
        assertEquals(new Vector(1.5, 65.6, 0), Boxes.nearest(golem, 4.5, 65.6, 0));
    }

    @Test
    @DisplayName("an arrow flying through a box this tick is caught where it enters; one that passes beside is not")
    void projectiles() {
        Vector entered = Boxes.crossed(golem, new Vector(-3, 66, 0), new Vector(2, 0, 0));

        assertNotNull(entered);
        assertEquals(-1.5, entered.getX(), 1e-9);
        assertNull(Boxes.crossed(golem, new Vector(-3, 66, 0), new Vector(1, 0, 0)), "not there yet");
        assertNull(Boxes.crossed(golem, new Vector(-3, 66, 2), new Vector(4, 0, 0)), "beside it");
        assertNull(Boxes.crossed(golem, new Vector(-3, 66, 0), new Vector(0, 0, 0)), "lying still");
        assertNotNull(Boxes.crossed(golem, new Vector(0, 66, 0), new Vector(0, 0, 0)), "already inside");
    }
}
