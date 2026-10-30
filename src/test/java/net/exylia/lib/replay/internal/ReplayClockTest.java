package net.exylia.lib.replay.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the wall clock a recording falls back to keeps moving on every thread.
 */
class ReplayClockTest {

    @AfterEach
    void back() {
        ReplayClock.useWall(false);
    }

    @Test
    @DisplayName("on the wall clock, a thread's first stamp is the time now and later stamps move on")
    void wallClockMovesOnEveryThread() throws Exception {
        ReplayClock.useWall(true);
        // A minute into the run: a stamp of zero is a clock stuck at its start.
        AtomicInteger first = new AtomicInteger();
        AtomicInteger later = new AtomicInteger();
        Thread fresh = new Thread(() -> {
            first.set(ReplayClock.now());
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            later.set(ReplayClock.now());
        });
        Thread.sleep(120);
        fresh.start();
        fresh.join();
        assertTrue(first.get() > 0, "the first stamp on a new thread is not zero: " + first.get());
        assertTrue(later.get() >= first.get() + 2, "it moves on: " + first.get() + " then " + later.get());
    }
}
