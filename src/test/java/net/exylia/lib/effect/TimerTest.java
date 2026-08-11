package net.exylia.lib.effect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the clock behind timed effects.
 *
 * <p>This is where an off-by-one shows up as a countdown that ends a tick early
 * or a bar that never quite empties, so the edges are checked explicitly.
 */
class TimerTest {

    @Test
    @DisplayName("a countdown reports the time it has left")
    void countdownReportsRemaining() {
        Timer timer = Timer.countdown(3.0);

        assertEquals(3.0, timer.displayed(), 0.0001);
        timer.advance(20);
        assertEquals(2.0, timer.displayed(), 0.0001);
    }

    @Test
    @DisplayName("a countdown works in decimals of a second")
    void countdownKeepsDecimals() {
        Timer timer = Timer.countdown(3.3);

        assertEquals(66, timer.remainingTicks(), "3.3s is 66 ticks");
        timer.advance(1);
        assertEquals(3.25, timer.displayed(), 0.0001);
    }

    @Test
    @DisplayName("a count-up reports the time it has run")
    void countUpReportsElapsed() {
        Timer timer = Timer.countUp();

        assertEquals(0.0, timer.displayed(), 0.0001);
        timer.advance(30);
        assertEquals(1.5, timer.displayed(), 0.0001);
    }

    @Test
    @DisplayName("an open-ended count-up never finishes")
    void countUpNeverFinishes() {
        Timer timer = Timer.countUp();

        timer.advance(20 * 60 * 60);

        assertFalse(timer.finished());
        assertEquals(1f, timer.progress(), 0.0001, "a bar with no total stays full");
    }

    @Test
    @DisplayName("a countdown empties the bar and a count-up fills it")
    void progressRunsTheRightWay() {
        Timer down = Timer.countdown(10);
        Timer up = Timer.countUp(10);

        assertEquals(1f, down.progress(), 0.0001);
        assertEquals(0f, up.progress(), 0.0001);

        down.advance(100);
        up.advance(100);

        assertEquals(0.5f, down.progress(), 0.0001);
        assertEquals(0.5f, up.progress(), 0.0001);
    }

    @Test
    @DisplayName("a countdown stops at zero rather than going negative")
    void countdownClamps() {
        Timer timer = Timer.countdown(1);

        timer.advance(500);

        assertEquals(0.0, timer.displayed(), 0.0001);
        assertEquals(0f, timer.progress(), 0.0001);
        assertTrue(timer.finished());
    }

    @Test
    @DisplayName("a countdown is finished exactly when it runs out")
    void finishesOnTheRightTick() {
        Timer timer = Timer.countdown(1.0);

        timer.advance(19);
        assertFalse(timer.finished(), "one tick left is not finished");

        timer.advance(1);
        assertTrue(timer.finished(), "zero left is finished");
    }

    @Test
    @DisplayName("extending gives a running countdown more time")
    void extendAddsTime() {
        Timer timer = Timer.countdown(5);
        timer.advance(20);
        assertEquals(4.0, timer.displayed(), 0.0001);

        timer.extend(Ticks.fromSeconds(3));

        assertEquals(7.0, timer.displayed(), 0.0001);
        assertFalse(timer.finished());
    }

    @Test
    @DisplayName("extending by a negative amount can end a countdown")
    void extendCanEnd() {
        Timer timer = Timer.countdown(5);

        timer.extend(-Ticks.fromSeconds(10));

        assertTrue(timer.finished());
    }

    @Test
    @DisplayName("extending an open-ended count-up does not turn it into a bounded one")
    void extendLeavesOpenEndedAlone() {
        Timer timer = Timer.countUp();

        timer.extend(Ticks.fromSeconds(5));
        timer.advance(Ticks.fromSeconds(60));

        assertFalse(timer.finished(), "an open-ended timer must stay open-ended");
    }

    @Test
    @DisplayName("a timer advanced from many threads counts every tick exactly once")
    void concurrentAdvanceIsExact() throws Exception {
        Timer timer = Timer.countUp();
        int threads = 8;
        int each = 1000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) {
                    timer.advance(1);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals((long) threads * each, timer.elapsedTicks(),
                "a lost update would make a countdown finish late");
    }
}
