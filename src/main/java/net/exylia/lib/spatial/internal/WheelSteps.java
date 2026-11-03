package net.exylia.lib.spatial.internal;

/**
 * The notches of the wheel a list still has to play, one item a step. The first notch plays at once; those
 * that come while steps are playing wait and play a beat apart, so a flick slides item by item. Notches the
 * other way cancel queued ones. Not thread-safe: one list's steps live on one thread.
 *
 * <p>Internal: free to change without notice.
 */
public final class WheelSteps {

    private final int cap;
    private int queued;
    private boolean playing;

    /** {@code cap}: how many steps it keeps waiting at most, so a long flick does not play on for seconds. */
    public WheelSteps(int cap) {
        this.cap = Math.max(1, cap);
    }

    /**
     * Queues {@code notches} (signed). True when nothing was playing: the caller plays {@link #next()} now and
     * asks for the next one after {@link #beat()} ticks, until it answers 0.
     */
    public boolean add(int notches) {
        queued = (int) Math.clamp((long) queued + notches, -cap, cap);
        if (playing || queued == 0) return false;
        playing = true;
        return true;
    }

    /** The next step, -1 or +1; 0 once none is left, which ends the playing. */
    public int next() {
        if (queued == 0) {
            playing = false;
            return 0;
        }
        int step = Integer.signum(queued);
        queued -= step;
        return step;
    }

    /** Ticks until the next step: one while a flick still waits, two for a last or lone step. */
    public long beat() {
        return Math.abs(queued) > 1 ? 1L : 2L;
    }

    /** Drops what waits (a page turn took over); a beat already asked for then ends the playing. */
    public void clear() {
        queued = 0;
    }

    /** The steps still waiting, signed. */
    public int queued() {
        return queued;
    }

    public boolean playing() {
        return playing;
    }
}
