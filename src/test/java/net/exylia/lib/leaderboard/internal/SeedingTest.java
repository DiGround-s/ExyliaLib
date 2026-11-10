package net.exylia.lib.leaderboard.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline reads go out a batch at a time, never before the last batch finished. */
class SeedingTest {

    @Test
    void handsOutOneBatchAtATime() {
        Seeding<Integer> seeding = new Seeding<>(List.of(1, 2, 3, 4, 5), 2);
        assertEquals(List.of(1, 2), seeding.next());
        assertEquals(List.of(), seeding.next());
        seeding.done();
        assertEquals(List.of(), seeding.next());
        seeding.done();
        assertEquals(List.of(3, 4), seeding.next());
        seeding.done();
        seeding.done();
        assertEquals(List.of(5), seeding.next());
        assertFalse(seeding.finished());
        seeding.done();
        assertTrue(seeding.finished());
        assertEquals(5, seeding.read());
    }
}
