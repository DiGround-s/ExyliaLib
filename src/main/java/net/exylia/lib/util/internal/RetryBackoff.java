package net.exylia.lib.util.internal;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Exponential backoff for retrying a failed read, plus a log that speaks once
 * per step rather than once per attempt.
 *
 * <p>A store that retries every online player's read on a fixed delay turns one
 * broken database into thousands of identical warnings. Each player's retries
 * wait {@link #delayTicks(int)} (5 s doubling to 5 min), and the store shares
 * one instance of this class, so the console hears about the outage once,
 * then again only after a growing quiet period, with how many failures it
 * skipped.
 *
 * <p>Safe from any thread.
 */
public final class RetryBackoff {

    private static final long FIRST_TICKS = 100L;
    private static final long CAP_TICKS = 6_000L;
    private static final long FIRST_QUIET_MILLIS = FIRST_TICKS * 50L;
    private static final long CAP_QUIET_MILLIS = CAP_TICKS * 50L;

    private final LongSupplier clock;
    private long quietMillis = FIRST_QUIET_MILLIS;
    private long nextLogAt;
    private int skipped;

    /** A backoff on the system clock. */
    public RetryBackoff() {
        this(System::currentTimeMillis);
    }

    /** A backoff on the given clock, in milliseconds; a seam for tests. */
    public RetryBackoff(@NotNull LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * How long the given retry waits.
     *
     * @param attempt how many attempts already failed, from 1
     * @return the delay in ticks: 5 s, 10 s, 20 s ... up to 5 min
     */
    public static long delayTicks(int attempt) {
        return Math.min(CAP_TICKS, FIRST_TICKS << Math.min(Math.max(attempt - 1, 0), 16));
    }

    /**
     * Reports a failure, unless one was already reported within the current
     * quiet period. The period doubles with every line written.
     *
     * @param message the line to write
     * @param log     where it goes
     */
    public void failed(@NotNull String message, @NotNull Consumer<String> log) {
        String line;
        synchronized (this) {
            long now = clock.getAsLong();
            if (now < nextLogAt) {
                skipped++;
                return;
            }
            line = skipped == 0 ? message
                    : message + " (" + skipped + " similar failures since the last report)";
            skipped = 0;
            nextLogAt = now + quietMillis;
            quietMillis = Math.min(quietMillis * 2, CAP_QUIET_MILLIS);
        }
        log.accept(line);
    }

    /** A read worked again: the next failure is a new outage and is reported at once. */
    public synchronized void succeeded() {
        quietMillis = FIRST_QUIET_MILLIS;
        nextLogAt = 0L;
        skipped = 0;
    }
}
