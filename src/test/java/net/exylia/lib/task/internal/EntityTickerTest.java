package net.exylia.lib.task.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.task.TaskHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTickerTest {

    private BukkitTaskScheduler tasks;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        tasks = new BukkitTaskScheduler(FakeServer.newPlugin("Ticker"));
    }

    @Test
    @DisplayName("every entity timer of a plugin shares one server task")
    void timersShareOneTask() {
        FakePlayer first = new FakePlayer("First");
        FakePlayer second = new FakePlayer("Second");
        AtomicInteger fast = new AtomicInteger();
        AtomicInteger slow = new AtomicInteger();

        TaskHandle a = tasks.runAtEntityTimer(first.player(), 1L, 1L, fast::incrementAndGet);
        TaskHandle b = tasks.runAtEntityTimer(second.player(), 2L, 4L, slow::incrementAndGet);
        assertEquals(1, FakeServer.liveRepeatingTasks());

        FakeServer.tick(1); // the shared task starts on the next tick
        FakeServer.tick(8);
        assertEquals(8, fast.get());
        assertEquals(2, slow.get(), "ticks 2 and 6");

        second.disconnect();
        FakeServer.tick(4);
        assertTrue(b.isCancelled(), "a gone player ends its timer");

        a.cancel();
        assertEquals(0, FakeServer.liveRepeatingTasks(), "an idle plugin keeps no task");
    }
}
