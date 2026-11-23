package net.exylia.lib.camera;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CameraWallsTest {

    @Test
    void stopsAtTheFirstSolidBlockAlongTheRay() {
        // A wall filling x = 3, the ray leaving x = 0.5 towards +x.
        assertEquals(2.5, CameraWalls.firstSolid(0.5, 64.5, 0.5, 1, 0, 0, 10, (x, y, z) -> x == 3), 1e-9);
    }

    @Test
    void walksDiagonallyAndDown() {
        double u = 1 / Math.sqrt(3);
        // The floor at y = 60, its top at y = 61, 3.5 blocks below.
        double t = CameraWalls.firstSolid(0.5, 64.5, 0.5, u, -u, u, 20, (x, y, z) -> y <= 60);
        assertEquals(3.5 * Math.sqrt(3), t, 1e-9);
    }

    @Test
    void ignoresTheStartingBlockAndAWallPastTheSeat() {
        assertEquals(4, CameraWalls.firstSolid(0.5, 64.5, 0.5, 0, 0, -1, 4,
                (x, y, z) -> z == 0 || z == -10), 1e-9);
    }

    @Test
    void movesInUntilATrunkNoLongerHidesTheOtherBody() {
        // Aiming at (0.5, 65, 0.5) from 4 blocks out along +x; the other body stands at
        // (0.5, 65, 2.5) and a trunk at x = 2, z = 1..2 stands between it and the far seat.
        Location aim = new Location(null, 0.5, 65, 0.5);
        Location seat = new Location(null, 4.5, 65, 0.5);
        Location other = new Location(null, 0.5, 65, 2.5);
        CameraWalls.Blocks trunk = (x, y, z) -> x == 2 && (z == 1 || z == 2);
        double reach = CameraWalls.reach(aim, seat, List.of(other), trunk);
        assertEquals(1.75, reach, 1e-9);
        // Nothing to keep in sight: the seat stays where it was.
        assertEquals(4, CameraWalls.reach(aim, seat, List.of(), trunk), 1e-9);
    }
}
