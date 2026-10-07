package net.exylia.lib.replay.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That deduplicating sounds stays cheap on the Netty threads.
 *
 * <p>A busy PvP server sends more distinct sounds in two ticks than the dedup
 * map holds. Sweeping that map on every packet stalled every connection and
 * showed up as players' ping jumping by hundreds of milliseconds.
 */
class ReplayCaptureTest {

    @Test
    @DisplayName("a packet heard twice in one tick is one sound")
    void deduplicates() {
        ReplayClock.useWall(true);
        assertTrue(ReplayCapture.first(-123_456));
        assertFalse(ReplayCapture.first(-123_456));
    }

    @Test
    @DisplayName("a flood of distinct sounds in one tick does not sweep the map per packet")
    void floodStaysCheap() {
        ReplayClock.useWall(true);
        // Before the fix this took minutes: every packet past the limit walked the whole map.
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            for (int key = 0; key < 200_000; key++) ReplayCapture.first(key);
        });
    }

    @Test
    @DisplayName("the sound key tells apart every field of the packet")
    void soundKeyUsesEveryField() {
        int base = ReplayCapture.soundKey(42, 1, 2, 3, 99L);
        assertEquals(base, ReplayCapture.soundKey(42, 1, 2, 3, 99L));
        assertNotEquals(base, ReplayCapture.soundKey(43, 1, 2, 3, 99L), "sound");
        assertNotEquals(base, ReplayCapture.soundKey(42, 9, 2, 3, 99L), "x");
        assertNotEquals(base, ReplayCapture.soundKey(42, 1, 9, 3, 99L), "y");
        assertNotEquals(base, ReplayCapture.soundKey(42, 1, 2, 9, 99L), "z");
        assertNotEquals(base, ReplayCapture.soundKey(42, 1, 2, 3, 98L), "seed");
    }
}
