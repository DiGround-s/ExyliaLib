package net.exylia.lib.util.mob.internal;

import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShapesTest {

    @Test
    @DisplayName("a bolt between two points that meet is a straight line, not an empty random range")
    void jaggedWithoutSwing() {
        Location at = new Location(null, 1, 2, 3);
        List<Location> path = Shapes.jagged(at, at.clone(), 3, 0, ThreadLocalRandom.current());
        assertEquals(4, path.size());
        for (Location point : path) {
            assertEquals(at, point);
        }
    }
}
