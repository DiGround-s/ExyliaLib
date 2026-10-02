package net.exylia.lib.database.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.task.Tasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
