package net.exylia.lib.database.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.task.Tasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutorTest {

    @BeforeEach
    void open() {
        FakeServer.install();
        FakeServer.reset();
        // The fake async scheduler is an unbounded cached pool, like the
        // server's, so it grows a thread for every task it is handed.
        FakeServer.runAsyncForReal();
    }

    @AfterEach
    void close() {
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a burst of blocked work queues instead of growing a thread per operation")
    void burstIsBounded() throws InterruptedException {
        TaskExecutor executor = new TaskExecutor(FakeServer.newPlugin("ExyliaEvents"));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(500);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        for (int i = 0; i < 500; i++) {
            executor.execute(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                finished.countDown();
            });
        }
        Thread.sleep(200);
        assertTrue(peak.get() <= 32, "ran " + peak.get() + " at once");

        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "queued work never ran");
        assertEquals(0, running.get());
    }

    @Test
    @DisplayName("with the database stuck, work past the cap is refused instead of piling up")
    void queueIsCapped() throws InterruptedException {
        TaskExecutor executor = new TaskExecutor(FakeServer.newPlugin("ExyliaEvents"));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        Runnable stuck = () -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            ran.incrementAndGet();
        };
        // Every runner taken, then the queue filled to the brim. Runners pull
        // their first task off the queue asynchronously, so a few slots may
        // free up; submitting until refused bounds the total either way.
        int accepted = 0;
        try {
            for (int i = 0; i < TaskExecutor.MAX_WAITING * 2; i++) {
                executor.execute(stuck);
                accepted++;
            }
        } catch (RejectedExecutionException expected) {
            // The point of the test.
        }
        assertTrue(accepted < TaskExecutor.MAX_WAITING * 2, "nothing was ever refused");
        assertTrue(accepted <= TaskExecutor.MAX_WAITING + 32, "accepted " + accepted);
        assertThrows(RejectedExecutionException.class, () -> executor.execute(stuck));

        release.countDown();
        int expected = accepted;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (ran.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, ran.get(), "accepted work must still run");
        // Drained: the cap is a ceiling on waiting work, not a lifetime budget.
        executor.execute(ran::incrementAndGet);
    }
}
