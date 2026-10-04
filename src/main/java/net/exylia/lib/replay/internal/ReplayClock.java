package net.exylia.lib.replay.internal;

import net.exylia.lib.platform.Platform;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

/**
 * The tick every sample and every mark is stamped with.
 *
 * <h2>Paper: the server's own tick</h2>
 * Every sample taken in one server tick reads the same number, so two players
 * sampled together are never recorded a tick apart, and a slow tick is one
 * frame rather than a gap or a doubled frame.
 *
 * <h2>Folia, and anything that stops counting</h2>
 * Folia has no server-wide tick. The clock there is wall time in fifty
 * millisecond steps, and a thread keeps the value it read for the rest of its
 * own tick, so everything one region samples in one of its ticks carries one
 * stamp. A stamp read twice in two ticks is moved on by the tape itself.
 *
 * <p>A clock that does not move is the worst failure a recorder can have:
 * every frame lands on one tick and a minute of recording is one frame. The
 * server's counter is therefore watched, and when it stops moving while time
 * passes the clock goes over to wall time for good.
 */
@ApiStatus.Internal
public final class ReplayClock {

    /** How long a thread keeps the value it read, in nanoseconds. */
    private static final long SAME_TICK_NANOS = 20_000_000L;

    /** How long the server's counter may stand still before it is not believed. */
    private static final long STUCK_NANOS = 3_000_000_000L;

    private static final long NANOS_PER_TICK = 50_000_000L;
    private static final long EPOCH = System.nanoTime();
    private static final ThreadLocal<long[]> CACHED = ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, 0});
    private static final boolean FOLIA = Platform.isFolia();

    private static volatile boolean wall = FOLIA;
    private static volatile int lastSeen = Integer.MIN_VALUE;
    private static volatile long lastChange = System.nanoTime();

    private ReplayClock() {
    }

    /** The current tick. Only differences between two readings mean anything. */
    public static int now() {
        long nanos = System.nanoTime();
        if (!wall) {
            int tick = serverTick();
            if (tick != lastSeen) {
                lastSeen = tick;
                lastChange = nanos;
                return tick;
            }
            if (nanos - lastChange < STUCK_NANOS) return tick;
            wall = true;
        }
        long[] cached = CACHED.get();
        if (nanos - cached[0] < SAME_TICK_NANOS) return (int) cached[1];
        cached[0] = nanos;
        cached[1] = (nanos - EPOCH) / NANOS_PER_TICK;
        return (int) cached[1];
    }

    private static int serverTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (RuntimeException unsupported) {
            wall = true;
            return lastSeen;
        }
    }
}
