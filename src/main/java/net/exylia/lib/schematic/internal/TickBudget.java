package net.exylia.lib.schematic.internal;

import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * How much of a tick one server thread may spend writing schematic blocks.
 *
 * <p>{@link #BUDGET_NANOS} of every {@link #WINDOW_NANOS}, per thread, shared
 * by every chunk of every paste that lands on that thread. Per thread rather
 * than per chunk, because two hundred chunk tasks all due on the same tick
 * would otherwise each spend the full budget, and per thread rather than
 * global, because on Folia two regions tick in parallel and neither should wait
 * on the other's arena.
 *
 * <p>The window is wall-clock rather than a tick counter: Paper, Folia and the
 * hybrids do not agree on what a tick number is per region thread, and fifty
 * milliseconds is what a tick is meant to last. A tick that overruns can see
 * two windows, so the worst case is twice the budget — still a few
 * milliseconds, never a stall.
 *
 * <p>Not thread-safe: each instance belongs to one thread.
 */
final class TickBudget {

    /**
     * Time a thread may spend on paste writes per window: 2.5 ms, five percent
     * of a tick. A full rewrite of an empty area is therefore slow on purpose —
     * tens of thousands of blocks a second per thread — while the usual arena
     * regeneration, a few hundred changed blocks, finishes within one tick.
     */
    static final long BUDGET_NANOS = 2_500_000L;

    /** One tick, the length the budget refills over. */
    static final long WINDOW_NANOS = 50_000_000L;

    private final LongSupplier clock;
    private long windowStart;
    private long used;
    private boolean started;

    TickBudget(LongSupplier clock) {
        this.clock = clock;
    }

    /** Whether there is anything left of this window, starting a new one if it is over. */
    boolean hasTime() {
        long now = clock.getAsLong();
        if (!started || now - windowStart >= WINDOW_NANOS) {
            started = true;
            windowStart = now;
            used = 0;
        }
        return used < BUDGET_NANOS;
    }

    /** Charges time spent outside {@link #run}, such as taking a snapshot. */
    void charge(long nanos) {
        used += nanos;
    }

    /** The clock, for timing work to {@link #charge}. */
    long now() {
        return clock.getAsLong();
    }

    /**
     * Runs units in order until they are done or the window is spent.
     *
     * <p>The clock is read once per batch rather than once per unit: a block
     * write is a microsecond or two, and timing each one would be a measurable
     * share of the cost. The overrun is at most one batch.
     *
     * @param from  the first unit to run
     * @param to    one past the last
     * @param batch how many units run between clock reads
     * @param unit  the work, given the unit's number
     * @return the first unit not run; {@code to} when everything ran
     */
    int run(int from, int to, int batch, IntConsumer unit) {
        int next = from;
        while (next < to) {
            if (!hasTime()) {
                return next;
            }
            long start = clock.getAsLong();
            int end = Math.min(to, next + batch);
            for (; next < end; next++) {
                unit.accept(next);
            }
            used += clock.getAsLong() - start;
        }
        return next;
    }
}
