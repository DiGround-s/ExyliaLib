package net.exylia.lib.util.teleport;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link TeleportRequest#tickSound}: once per whole second, though ticks come four times a second. */
class TickSoundTest {

    @Test
    void playsOncePerWholeSecond() {
        AtomicInteger played = new AtomicInteger();
        DoubleConsumer ticks = TeleportRequest.everySecond(played::incrementAndGet);
        for (double left : new double[]{3.0, 2.75, 2.5, 2.25, 2.0, 1.75, 1.5, 1.25, 1.0, 0.75, 0.5, 0.25}) {
            ticks.accept(left);
        }
        assertEquals(3, played.get());
    }
}
