package net.exylia.lib.screen.internal;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardSeatTest {

    @Test
    void sitsAheadOfTheEyesFacingBack() {
        // Yaw 0 looks south (+z): the board stands 2 blocks south, facing north, upright whatever the pitch.
        Location seat = WorldBoard.seat(new Location(null, 0, 65, 0, 0, 70), 2);
        assertEquals(0, seat.getX(), 1e-9);
        assertEquals(2, seat.getZ(), 1e-9);
        assertEquals(65, seat.getY(), 1e-9);
        assertEquals(180, Math.abs(seat.getYaw()), 1e-4);
        assertEquals(0, seat.getPitch(), 1e-9);
        // Yaw 90 looks west (-x).
        Location west = WorldBoard.seat(new Location(null, 0, 65, 0, 90, 0), 2);
        assertEquals(-2, west.getX(), 1e-9);
        assertEquals(0, west.getZ(), 1e-9);
    }

    @Test
    void followsOnlyPastAWalkOrATurn() {
        assertFalse(WorldBoard.strayed(1, 20));
        assertTrue(WorldBoard.strayed(1.6 * 1.6, 0));
        assertTrue(WorldBoard.strayed(0, 60));
        assertTrue(WorldBoard.strayed(0, -60));
        // 350 degrees of difference is 10 degrees the other way round.
        assertFalse(WorldBoard.strayed(0, 350));
    }
}
