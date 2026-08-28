package net.exylia.lib;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.internal.BukkitTaskScheduler;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the Bukkit implementation against a fake server to verify the behaviour
 * the documentation promises: cleanup, exception isolation, self-cancellation
 * and inline execution.
 */
class BukkitTaskSchedulerTest {

    private Plugin plugin;
    private TaskScheduler tasks;

    @BeforeAll
    static void installServer() {
        FakeServer.install();
    }

    @BeforeEach
    void setUp() {
        FakeServer.reset();
        plugin = FakeServer.newPlugin("TestPlugin");
        tasks = new BukkitTaskScheduler(plugin);
    }

    @Test
    void oneShotTaskRunsAndStopsBeingTracked() {
        AtomicInteger runs = new AtomicInteger();
        tasks.run(runs::incrementAndGet);

        assertEquals(1, tasks.activeTasks(), "task should be tracked until it runs");

        FakeServer.SCHEDULED.get(0).tick();

        assertEquals(1, runs.get());
        assertEquals(0, tasks.activeTasks(), "a finished task should be released");
    }

    @Test
    void cancelAllStopsEveryTask() {
        tasks.runTimer(0L, 20L, () -> { });
        tasks.runTimer(0L, 20L, () -> { });
        tasks.runAsync(() -> { });

        assertEquals(3, tasks.activeTasks());

        tasks.cancelAll();

        assertEquals(0, tasks.activeTasks());
        assertTrue(FakeServer.SCHEDULED.stream().allMatch(s -> s.cancelled),
                "every underlying platform task should be cancelled");
    }

    @Test
    void cancelledTimerStopsRunning() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandle handle = tasks.runTimer(0L, 20L, runs::incrementAndGet);

        FakeServer.SCHEDULED.get(0).tick();
        handle.cancel();
        FakeServer.SCHEDULED.get(0).tick();

        assertEquals(1, runs.get(), "no execution should happen after cancel");
    }

    @Test
    void aThrowingTaskDoesNotEscapeIntoTheScheduler() {
        tasks.run(() -> {
            throw new IllegalStateException("boom");
        });

        // The scheduler must not see the exception, or the server logs it as a
        // scheduler fault and the task stays tracked forever.
        FakeServer.SCHEDULED.get(0).tick();

        assertEquals(0, tasks.activeTasks(), "a failed task should still be released");
    }

    @Test
    void aThrowingTimerCancelsItself() {
        AtomicInteger runs = new AtomicInteger();
        tasks.runTimer(0L, 20L, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        FakeServer.SCHEDULED.get(0).tick();
        FakeServer.SCHEDULED.get(0).tick();

        assertEquals(1, runs.get(), "a broken timer must not keep repeating");
        assertEquals(0, tasks.activeTasks());
    }

    @Test
    void timerCanCancelItselfThroughItsHandle() {
        AtomicInteger runs = new AtomicInteger();
        tasks.runTimer(0L, 20L, handle -> {
            if (runs.incrementAndGet() >= 3) {
                handle.cancel();
            }
        });

        FakeServer.Scheduled scheduled = FakeServer.SCHEDULED.get(0);
        for (int i = 0; i < 10; i++) {
            scheduled.tick();
        }

        assertEquals(3, runs.get(), "the timer should stop itself on the third run");
        assertEquals(0, tasks.activeTasks());
    }

    @Test
    void executeRunsInlineOnTheMainThread() {
        FakeServer.setPrimaryThread(true);
        AtomicInteger runs = new AtomicInteger();

        tasks.execute(runs::incrementAndGet);

        assertEquals(1, runs.get(), "should run immediately, not next tick");
        assertTrue(FakeServer.SCHEDULED.isEmpty(), "nothing should be queued");
    }

    @Test
    void executeDefersWhenOffTheMainThread() {
        FakeServer.setPrimaryThread(false);
        AtomicInteger runs = new AtomicInteger();

        tasks.execute(runs::incrementAndGet);

        assertEquals(0, runs.get(), "must not run on the calling thread");
        assertEquals(1, FakeServer.SCHEDULED.size(), "should be queued for the main thread");

        FakeServer.SCHEDULED.get(0).tick();
        assertEquals(1, runs.get());
    }

    @Test
    void threadChecksFollowTheMainThread() {
        FakeServer.setPrimaryThread(true);
        assertTrue(tasks.isGlobalThread());

        FakeServer.setPrimaryThread(false);
        assertFalse(tasks.isGlobalThread());
    }

    @Test
    void aOneShotFromADisabledPluginRunsInline() {
        FakeServer.disable(plugin);
        AtomicInteger runs = new AtomicInteger();

        tasks.run(runs::incrementAndGet);

        // The real scheduler throws here, which on a server is a shutdown path
        // dying halfway: regions never released, players never sent home.
        assertEquals(1, runs.get(), "work asked for during disable should still run");
        assertTrue(FakeServer.SCHEDULED.isEmpty(), "nothing can be queued for a stopped plugin");
        assertEquals(0, tasks.activeTasks(), "an inline task is finished when it returns");
    }

    @Test
    void anAsyncTaskFromADisabledPluginRunsInline() {
        FakeServer.disable(plugin);
        AtomicInteger runs = new AtomicInteger();

        // The database module's executor is this call, and a plugin's last save
        // rides on it.
        tasks.runAsync(runs::incrementAndGet);

        assertEquals(1, runs.get(), "a shutdown save must still reach the database");
        assertTrue(FakeServer.SCHEDULED.isEmpty());
    }

    @Test
    void aTimerFromADisabledPluginIsDroppedRatherThanThrown() {
        FakeServer.disable(plugin);
        AtomicInteger runs = new AtomicInteger();

        TaskHandle handle = tasks.runTimer(0L, 20L, runs::incrementAndGet);

        assertEquals(0, runs.get(), "there is no later left for a timer to run in");
        assertTrue(handle.isCancelled(), "the caller should get a dead handle, not an exception");
        assertTrue(FakeServer.SCHEDULED.isEmpty());
        assertEquals(0, tasks.activeTasks());
    }
}
