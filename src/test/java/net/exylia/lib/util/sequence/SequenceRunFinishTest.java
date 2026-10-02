package net.exylia.lib.util.sequence;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run whose frames were still scheduled when its last step played used to
 * wait for a callback nobody made, so it never finished and its plugin kept it,
 * and every player it targeted, for as long as the server ran.
 */
class SequenceRunFinishTest {

    @Test
    void finishesOnceTheFramesItOwnsHaveRun() {
        List<Consumer<TaskHandle>> timers = new ArrayList<>();
        AtomicInteger finished = new AtomicInteger();
        SequenceRun run = SequenceFactory.run(scheduler(timers), finished::incrementAndGet);

        Handle frame = new Handle();
        run.owns(frame);
        run.finishWhenTrailsEnd();
        assertEquals(0, finished.get(), "a frame is still to draw");
        assertEquals(1, timers.size());

        Handle check = new Handle();
        timers.get(0).accept(check);
        assertEquals(0, finished.get());

        frame.done = true;
        timers.get(0).accept(check);
        assertEquals(1, finished.get());
        assertTrue(run.isFinished());
        assertTrue(check.done, "the check stops itself");
    }

    @Test
    void finishesAtOnceWhenNothingIsPending() {
        List<Consumer<TaskHandle>> timers = new ArrayList<>();
        AtomicInteger finished = new AtomicInteger();
        SequenceRun run = SequenceFactory.run(scheduler(timers), finished::incrementAndGet);

        Handle ran = new Handle();
        ran.done = true;
        run.owns(ran);
        run.finishWhenTrailsEnd();

        assertEquals(1, finished.get());
        assertTrue(timers.isEmpty());
    }

    private static TaskScheduler scheduler(List<Consumer<TaskHandle>> timers) {
        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(), new Class<?>[] {TaskScheduler.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("runTimer") && args.length == 3
                            && args[2] instanceof Consumer<?> consumer) {
                        @SuppressWarnings("unchecked")
                        Consumer<TaskHandle> timer = (Consumer<TaskHandle>) consumer;
                        timers.add(timer);
                        return new Handle();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Handle implements TaskHandle {
        boolean done;

        @Override
        public void cancel() {
            done = true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public boolean isRepeating() {
            return false;
        }
    }
}
