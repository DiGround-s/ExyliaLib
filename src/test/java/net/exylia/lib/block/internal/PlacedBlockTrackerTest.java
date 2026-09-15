package net.exylia.lib.block.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record a chunk keeps. The chunk's persistent data needs a server; what
 * is written into it does not, and that is where the mistakes would be.
 */
class PlacedBlockTrackerTest {

    @Test
    @DisplayName("every position in a chunk packs to its own number, negative heights included")
    void packingIsExact() {
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -64; y < 320; y += 7) {
                    assertTrue(seen.add(PlacedBlockTracker.pack(x, y, z)), "collision at " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals(PlacedBlockTracker.pack(3, 70, 5), PlacedBlockTracker.pack(-13, 70, 21),
                "world coordinates reduce to the chunk-local position");
    }

    @Test
    @DisplayName("recording is idempotent, forgetting removes exactly one")
    void addAndRemove() {
        int[] none = new int[0];
        int[] one = PlacedBlockTracker.with(none, 11);
        int[] two = PlacedBlockTracker.with(one, 22);
        assertSame(two, PlacedBlockTracker.with(two, 11));
        assertArrayEquals(new int[]{11, 22}, two);

        assertArrayEquals(new int[]{22}, PlacedBlockTracker.without(two, 11));
        assertSame(two, PlacedBlockTracker.without(two, 33));
    }

    @Test
    @DisplayName("a full chunk forgets its oldest position, never grows past the cap")
    void fullChunkDropsOldest() {
        int[] positions = new int[0];
        for (int index = 0; index < PlacedBlockTracker.MAX_PER_CHUNK; index++) {
            positions = PlacedBlockTracker.with(positions, index);
        }
        assertEquals(PlacedBlockTracker.MAX_PER_CHUNK, positions.length);

        positions = PlacedBlockTracker.with(positions, -1);

        assertEquals(PlacedBlockTracker.MAX_PER_CHUNK, positions.length);
        assertEquals(-1, PlacedBlockTracker.indexOf(positions, 0), "the oldest is gone");
        assertEquals(PlacedBlockTracker.MAX_PER_CHUNK - 1, PlacedBlockTracker.indexOf(positions, -1));
    }
}
