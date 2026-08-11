package net.exylia.lib.effect.internal;

import net.exylia.lib.effect.Ticks;
import net.exylia.lib.effect.Timer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A timer counted in ticks.
 *
 * <p>Ticks rather than wall-clock time, because an effect is driven by the
 * server's own scheduler: counting milliseconds would drift away from the ticks
 * that actually redraw the bar, and a countdown would finish while the bar still
 * showed time left.
 *
 * <p>The elapsed count is atomic so a display thread can read a timer that a
 * scheduler thread is advancing. Reads are of a single value, so they are always
 * of a real moment rather than a half-updated one.
 */
public final class SimpleTimer implements Timer {

    /** Not final: extend() moves the finish line while the timer runs. */
    private final AtomicLong totalTicks;
    private final boolean countdown;
    private final AtomicLong elapsed = new AtomicLong();

    /**
     * Whether this timer has an end at all.
     *
     * <p>Kept apart from the total because the two mean different things that a
     * single zero cannot express: an open-ended count-up has no end, while a
     * countdown shortened to nothing has reached one.
     */
    private final boolean bounded;

    /**
     * @param totalTicks the duration, or zero for an open-ended count-up
     * @param countdown  whether it runs towards zero
     */
    public SimpleTimer(long totalTicks, boolean countdown) {
        this.totalTicks = new AtomicLong(Math.max(0, totalTicks));
        this.countdown = countdown;
        this.bounded = totalTicks > 0;
    }

    @Override
    public void advance(long ticks) {
        if (ticks <= 0) {
            return;
        }
        long total = totalTicks.get();
        elapsed.updateAndGet(current -> {
            long next = current + ticks;
            // Clamping here rather than at read time keeps a finished timer from
            // growing without bound while its effect is still being cleaned up.
            return bounded && next > total ? total : next;
        });
    }

    @Override
    public void extend(long ticks) {
        if (ticks == 0) {
            return;
        }
        if (!bounded) {
            // An open-ended count-up has no finish line to move, so extending it
            // would silently turn it into a bounded one.
            return;
        }
        totalTicks.updateAndGet(current -> Math.max(0, current + ticks));
    }

    @Override
    public double remaining() {
        return Ticks.toSeconds(remainingTicks());
    }

    @Override
    public double elapsed() {
        return Ticks.toSeconds(elapsed.get());
    }

    @Override
    public long remainingTicks() {
        if (!bounded) {
            return 0;
        }
        return Math.max(0, totalTicks.get() - elapsed.get());
    }

    @Override
    public long elapsedTicks() {
        return elapsed.get();
    }

    @Override
    public double total() {
        return Ticks.toSeconds(totalTicks.get());
    }

    @Override
    public float progress() {
        long total = totalTicks.get();
        if (!bounded) {
            // Nothing to measure against, so a bar stays full rather than
            // flickering at zero.
            return 1f;
        }
        if (total == 0) {
            // Shortened to nothing: a countdown is empty, a count-up is full.
            return countdown ? 0f : 1f;
        }
        float done = (float) elapsed.get() / total;
        float value = countdown ? 1f - done : done;
        return Math.clamp(value, 0f, 1f);
    }

    @Override
    public boolean finished() {
        return bounded && elapsed.get() >= totalTicks.get();
    }

    @Override
    public boolean isCountdown() {
        return countdown;
    }

    @Override
    public double displayed() {
        return countdown ? remaining() : elapsed();
    }

    @Override
    public String toString() {
        return "Timer[" + (countdown ? "down " : "up ") + displayed() + "s]";
    }
}
