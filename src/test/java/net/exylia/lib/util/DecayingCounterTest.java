package net.exylia.lib.util;

import net.exylia.lib.redis.Message;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A volume halves once per half-life, adds on top of what is left, and peers' batches age before they land. */
class DecayingCounterTest {

    private static final long HOUR = 3_600_000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final DecayingCounter counter = new DecayingCounter(Duration.ofHours(2), now::get, false);

    @Test
    void decayHalvesOncePerHalfLife() {
        assertEquals(100, DecayingCounter.decay(100, 0, 2 * HOUR), 1e-9);
        assertEquals(50, DecayingCounter.decay(100, 2 * HOUR, 2 * HOUR), 1e-9);
        assertEquals(25, DecayingCounter.decay(100, 4 * HOUR, 2 * HOUR), 1e-9);
        assertEquals(100 / Math.sqrt(2), DecayingCounter.decay(100, HOUR, 2 * HOUR), 1e-9);
    }

    @Test
    void aClockSteppingBackDecaysNothing() {
        assertEquals(100, DecayingCounter.decay(100, -HOUR, 2 * HOUR), 1e-9);
    }

    @Test
    void addsLandOnWhatIsLeft() {
        counter.add("ore", 100);
        now.addAndGet(2 * HOUR);
        assertEquals(50, counter.value("ore"), 1e-9);
        counter.add("ore", 50);
        assertEquals(100, counter.value("ore"), 1e-9);
        now.addAndGet(2 * HOUR);
        assertEquals(50, counter.value("ore"), 1e-9);
        assertEquals(0, counter.value("never"), 0);
    }

    @Test
    void theValueNeverGoesNegative() {
        counter.add("ore", 10);
        counter.add("ore", -25);
        assertEquals(0, counter.value("ore"), 0);
    }

    @Test
    void valuesAreDecayedToNow() {
        counter.add("a", 8);
        counter.add("b", 4);
        now.addAndGet(2 * HOUR);
        assertEquals(4, counter.values().get("a"), 1e-9);
        assertEquals(2, counter.values().get("b"), 1e-9);
        counter.reset("a");
        assertEquals(0, counter.value("a"), 0);
    }

    @Test
    void aPeerBatchIsAgedBeforeItIsAdded() {
        long sent = now.get();
        now.addAndGet(2 * HOUR);
        counter.receive(new Message("server-2", sent + "\n40.0\tore\nbroken line\n10.0\twith\ttab", false));
        assertEquals(20, counter.value("ore"), 1e-9);
        assertEquals(5, counter.value("with\ttab"), 1e-9);
    }

    @Test
    void ownBatchesAreIgnored() {
        counter.receive(new Message("me", now.get() + "\n40.0\tore", true));
        assertEquals(0, counter.value("ore"), 0);
    }

    @Test
    void aHalfLifeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> DecayingCounter.local(Duration.ZERO));
    }
}
