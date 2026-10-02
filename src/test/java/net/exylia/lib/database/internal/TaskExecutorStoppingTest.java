package net.exylia.lib.database.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.task.Tasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Paper starts an async task only on a main-thread tick, so work handed to the
 * scheduler while the server stops never starts, and a disable that waits for
 * a final save waits out its whole timeout.
 */
class TaskExecutorStoppingTest {

    @BeforeEach
    void open() {
        FakeServer.install();
        FakeServer.reset();
    }

    @AfterEach
    void close() {
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("work handed over while the server stops runs at once, on the caller")
    void stoppingRunsInline() {
        FakeServer.setStopping(true);
        TaskExecutor executor = new TaskExecutor(FakeServer.newPlugin("ExyliaSurvivalCore"));
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        executor.execute(() -> ranOn.set(Thread.currentThread()));

        assertSame(Thread.currentThread(), ranOn.get());
    }
}
