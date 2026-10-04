package net.exylia.lib.replay.internal;

import net.exylia.lib.platform.Platform;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tick every sample and every mark is stamped with.
 *
 * <h2>Server ticks, not wall-clock time</h2>
 * Recordings used to stamp a sample with {@code nanoTime / 50 ms}. A server tick
 * is not fifty milliseconds: one runs at 48, the next at 53, a slow one at 90.
 * Two consecutive ticks then landed on the same stamp (the second overwrote the
 * first and the gap was filled with a copy), and two players sampled in the
 * same tick could straddle a boundary and be recorded a tick apart. Played back,
 * that is a body that freezes for a frame and then jumps, and a hit that lands
 * on somebody a step away from where they were.
 *
 * <p>Paper has one tick counter for the whole server, and every sample taken in
 * one tick reads the same number from it.
 *
 * <h2>Folia</h2>
 * Folia ticks every region on its own. The clock there is a counter advanced by
 * the global region, and a region thread caches what it read for the rest of
 * its own tick, so everything one region samples in one tick carries one
 * stamp. Two regions are never close enough for a one-tick disagreement between
 * them to show.
 */
@ApiStatus.Internal
public final class ReplayClock {

    /** How long a region thread keeps the value it read, in nanoseconds. */
    private static final long SAME_TICK_NANOS = 20_000_000L;

    private static final AtomicInteger GLOBAL = new AtomicInteger();
    private static final ThreadLocal<long[]> CACHED = ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, 0});
    private static final boolean FOLIA = Platform.isFolia();

    private ReplayClock() {
    }

    /** Advances the Folia counter; called once a tick by the global region. */
    static void advance() {
        GLOBAL.incrementAndGet();
    }

    /** The current tick. Only differences between two readings mean anything. */
    public static int now() {
        if (!FOLIA) {
            return Bukkit.getCurrentTick();
        }
        long[] cached = CACHED.get();
        long nanos = System.nanoTime();
        if (nanos - cached[0] < SAME_TICK_NANOS) {
            return (int) cached[1];
        }
        cached[0] = nanos;
        cached[1] = GLOBAL.get();
        return (int) cached[1];
    }
}
