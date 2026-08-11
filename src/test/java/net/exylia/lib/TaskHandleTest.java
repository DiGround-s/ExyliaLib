package net.exylia.lib;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.internal.TestHandles;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the handle state machine, which is the part of the task module that can
 * be exercised without a running server.
 */
class TaskHandleTest {

    @Test
    void cancelStopsTheBoundTask() {
        AtomicInteger cancels = new AtomicInteger();
        TestHandles.Fixture fixture = TestHandles.create(true);
        TestHandles.bind(fixture.handle(), cancels::incrementAndGet);

        fixture.handle().cancel();

        assertEquals(1, cancels.get(), "the platform task should be cancelled once");
        assertTrue(fixture.handle().isCancelled());
        assertTrue(fixture.registry().isEmpty(), "a cancelled task should stop being tracked");
    }

    @Test
    void cancelIsIdempotent() {
        AtomicInteger cancels = new AtomicInteger();
        TestHandles.Fixture fixture = TestHandles.create(true);
        TestHandles.bind(fixture.handle(), cancels::incrementAndGet);

        fixture.handle().cancel();
        fixture.handle().cancel();
        fixture.handle().cancel();

        assertEquals(1, cancels.get(), "repeated cancels must not reach the platform again");
    }

    @Test
    void cancelBeforeBindStillCancelsTheTask() {
        // A task can begin running, and be cancelled, before the scheduler call
        // that created it has returned and bound the platform task.
        AtomicInteger cancels = new AtomicInteger();
        TestHandles.Fixture fixture = TestHandles.create(true);

        fixture.handle().cancel();
        TestHandles.bind(fixture.handle(), cancels::incrementAndGet);

        assertEquals(1, cancels.get(), "binding after a cancel must cancel immediately");
    }

    @Test
    void completedTaskIsUntrackedButNotCancelled() {
        TestHandles.Fixture fixture = TestHandles.create(false);
        TestHandles.bind(fixture.handle(), () -> { });

        TestHandles.complete(fixture.handle());

        assertFalse(fixture.handle().isCancelled(), "finishing normally is not cancelling");
        assertTrue(fixture.registry().isEmpty(), "a finished task should stop being tracked");
    }

    @Test
    void repeatingFlagIsReported() {
        assertTrue(TestHandles.create(true).handle().isRepeating());
        assertFalse(TestHandles.create(false).handle().isRepeating());
    }

    @Test
    void handleIsUsableThroughThePublicInterface() {
        TaskHandle handle = TestHandles.create(true).handle();
        handle.cancel();
        assertTrue(handle.isCancelled());
    }
}
