package net.exylia.lib.stage;

import net.exylia.lib.packet.Volume;
import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnclosureTest {

    @Test
    @DisplayName("the floor layer is the block holding the feet up, on a full block, a slab, a carpet or farmland")
    void floorLayer() {
        assertEquals(63, Enclosure.floorLayer(64.0));
        assertEquals(63, Enclosure.floorLayer(63.5));      // a bottom slab at 63
        assertEquals(63, Enclosure.floorLayer(63.9375));   // farmland, a path
        assertEquals(63, Enclosure.floorLayer(64.0625));   // a carpet at 64 on a block at 63
    }

    @Test
    @DisplayName("what holds the player up stays real; the block the feet stand in is cleared (the old bug cleared a slab floor)")
    void floorStays() {
        // Standing on a bottom slab at 63: feet at 63.5, the slab's collision 0.5 tall.
        assertEquals(Enclosure.Fill.REAL, Enclosure.fill(63, 63, 63.5, true, 0.5));
        // A carpet the feet stand on, one layer up.
        assertEquals(Enclosure.Fill.REAL, Enclosure.fill(64, 63, 64.0625, true, 0.0625));
        // Soul sand, its collision under its outline.
        assertEquals(Enclosure.Fill.REAL, Enclosure.fill(63, 63, 63.875, true, 0.875));
        // A full block under the feet.
        assertEquals(Enclosure.Fill.REAL, Enclosure.fill(63, 63, 64.0, true, 1.0));
    }

    @Test
    @DisplayName("nothing to stand on in the floor layer becomes a barrier; over it, everything is air")
    void fills() {
        assertEquals(Enclosure.Fill.BARRIER, Enclosure.fill(63, 63, 64.0, false, 0));     // void, water, grass
        assertEquals(Enclosure.Fill.BARRIER, Enclosure.fill(63, 63, 63.5, true, 1.0));    // a full block poking past a slab floor
        assertEquals(Enclosure.Fill.AIR, Enclosure.fill(64, 63, 64.0, true, 1.0));        // a wall
        assertEquals(Enclosure.Fill.AIR, Enclosure.fill(64, 63, 64.0, true, 1.5));        // a fence
        assertEquals(Enclosure.Fill.AIR, Enclosure.fill(64, 63, 64.0, false, 0));         // a flower, a sign, a banner
        assertEquals(Enclosure.Fill.AIR, Enclosure.fill(65, 63, 64.0, true, 0.5625));     // a bed, a door's top half
        assertEquals(Enclosure.Fill.REAL, Enclosure.fill(62, 63, 64.0, true, 1.0));       // under the floor
    }

    @Test
    @DisplayName("the room runs from the floor layer to the ceiling over the eyes, as wide as its walls")
    void volume() {
        Volume v = Enclosure.volume(new Location(null, 0.5, 63.5, 0.5, 0f, 0), new EnclosureTheme());
        assertEquals(63.0, v.bottom(), 1e-9);
        assertEquals(63.5 + 1.62 + 3.5, v.top(), 1e-9);
        assertEquals(4.5, v.reach(), 1e-9);
        assertTrue(v.holds(0.5, 64, 0.5));
    }
}
