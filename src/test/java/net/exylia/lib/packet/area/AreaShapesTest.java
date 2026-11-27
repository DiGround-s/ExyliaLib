package net.exylia.lib.packet.area;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reveal rings go outward and keep every block; entrances sit on the edge that faces the way in. */
class AreaShapesTest {

    @Test
    void ringsGoOutwardAndKeepEveryBlock() {
        List<Location> blocks = new ArrayList<>();
        for (int x = 0; x < 15; x++) for (int z = 0; z < 15; z++) blocks.add(new Location(null, x, 64, z));
        List<List<Location>> rings = AreaLooks.rings(blocks, 7, 14, 5);
        assertEquals(5, rings.size());
        assertEquals(blocks.size(), rings.stream().mapToInt(List::size).sum());
        double previous = -1;
        for (List<Location> ring : rings) {
            if (ring.isEmpty()) continue;
            double nearest = Double.MAX_VALUE, farthest = 0;
            for (Location b : ring) {
                double d = Math.hypot(b.getBlockX() - 7, b.getBlockZ() - 14);
                nearest = Math.min(nearest, d);
                farthest = Math.max(farthest, d);
            }
            assertTrue(nearest >= previous, "rings do not overlap going out");
            previous = farthest - 1e-9;
        }
        assertTrue(rings.get(0).stream().anyMatch(b -> b.getBlockX() == 7 && b.getBlockZ() == 14));
    }

    @Test
    void facingPicksTheEdgeTowardsThePoint() {
        AreaWalls.Entrance west = AreaWalls.facing(10, 0, 20, 10, 0, 5);
        assertEquals(new AreaWalls.Entrance(10, 5, true, -1), west);
        assertEquals(10, west.x());
        assertEquals(5, west.z());
        assertEquals(new AreaWalls.Entrance(10, 15, false, 1), AreaWalls.facing(10, 0, 20, 10, 15, 30));
    }

    @Test
    void alongRoadFacesTheRoadOrNone() {
        // A road running north-south (yaw 0) at x = 0: the area east of it opens on its west edge.
        assertEquals(new AreaWalls.Entrance(10, 5, true, -1), AreaWalls.alongRoad(10, 0, 20, 10, 0, 0, 0));
        assertEquals(new AreaWalls.Entrance(20, 5, true, 1), AreaWalls.alongRoad(10, 0, 20, 10, 30, 0, 180));
        assertNull(AreaWalls.alongRoad(10, 0, 20, 10, 15, 0, 0), "the road runs through it");
    }

    @Test
    void outsideIsJustPastTheEdge() {
        Location at = new AreaWalls.Entrance(10, 5, true, -1).outside(null, 64, 1);
        assertEquals(9.5, at.getX());
        assertEquals(65, at.getY());
        assertEquals(5.5, at.getZ());
    }
}
