package net.exylia.lib.screen.internal;

/**
 * The bookkeeping of a polled cursor: the server asks a client for its rotation many times a frame (a relative
 * rotation of nothing, which it answers at once), so the cursor moves every frame instead of every tick.
 *
 * <p>A client handles every packet that arrived before a frame, then turns with the mouse. So the answers of one frame
 * repeat each other, and the gap from one changed answer to the next is a frame: that is how fast it draws, and how
 * fast it is worth asking. The pitch stops at 90 degrees, so it is put back now and then with an absolute rotation to
 * {@link #SENTINEL_YAW}, {@link #SENTINEL_PITCH}; the answer to that one is exactly those values, which says where the
 * new reckoning starts without a teleport and its confirm. Answers still on their way from before it count from the old
 * place.
 *
 * <p>Pure, so it is tested without a client. Times are {@link System#nanoTime()} nanoseconds.
 *
 * <p>Internal: free to change without notice.
 */
final class CursorPoll {

    /** A yaw the mouse cannot land on exactly: the answer that carries it is the first of a new reckoning. */
    static final float SENTINEL_YAW = 1e-6f;
    static final float SENTINEL_PITCH = 0f;
    /** The fastest a client is asked: 250 a second. */
    static final long FASTEST = 4_000_000L;
    /** The slowest a moving client is asked: once a 60 fps frame. */
    static final long SLOWEST = 16_666_667L;
    /** How often a still client is asked: enough to see the first move a frame or two late. */
    static final long IDLE = 40_000_000L;
    /** How long without a change makes a client still. */
    static final long IDLE_AFTER = 300_000_000L;
    /** A gap longer than this between two changes is a pause of the hand, not a frame. */
    private static final long LONGEST_FRAME = 100_000_000L;
    /** A reset the client never answered is sent again after this. */
    private static final long RESEND_AFTER = 1_000_000_000L;
    /** How much faster than a frame the client is asked, so every frame has an answer to give. */
    private static final double AHEAD = 1.25;

    /** How long changed answers are counted for one measure of the frame. */
    private static final long WINDOW = 250_000_000L;

    private boolean pending;
    private long pendingSince;
    private double frame = FASTEST;
    private long lastChange = -1;
    /** The measure under way: since when changed answers have come with no pause, and how many. */
    private long windowFrom = -1;
    private int windowCount;

    /** An absolute rotation to the sentinel was sent. */
    void reset(long now) {
        pending = true;
        pendingSince = now;
    }

    /** Whether a reset is on its way and its answer has not come. */
    boolean pending() {
        return pending;
    }

    /** Whether this answer is the reset's own: from it on, turns count from the sentinel. */
    boolean landed(float yaw, float pitch) {
        if (!pending || yaw != SENTINEL_YAW || pitch != SENTINEL_PITCH) return false;
        pending = false;
        return true;
    }

    /** Whether the reset went unanswered long enough to send it again. */
    boolean stale(long now) {
        return pending && now - pendingSince > RESEND_AFTER;
    }

    /**
     * Takes an answer. While the hand moves without a pause, changed answers come once a frame (or once an ask, when
     * asked slower than that), so their count over a quarter second measures the frame; a pause starts the count over.
     */
    void answered(long now, boolean changed) {
        if (!changed) return;
        if (lastChange < 0 || now - lastChange > LONGEST_FRAME) {
            windowFrom = now;
            windowCount = 0;
        } else {
            windowCount++;
            if (now - windowFrom >= WINDOW) {
                frame += ((double) (now - windowFrom) / windowCount - frame) * 0.5;
                windowFrom = now;
                windowCount = 0;
            }
        }
        lastChange = now;
    }

    /** About how long the client takes to draw a frame, in milliseconds. */
    double frameMillis() {
        return frame / 1e6;
    }

    /** When the client was last seen turning, or -1. */
    long lastChange() {
        return lastChange;
    }

    /**
     * How long to wait before asking again. A little faster than a frame while the hand moves: a client asked slower
     * than it draws answers once an ask, which reads as that many frames, so asking faster finds its real rate.
     */
    long period(long now) {
        if (lastChange < 0 || now - lastChange > IDLE_AFTER) return IDLE;
        return Math.max(FASTEST, Math.min(SLOWEST, (long) (frame / AHEAD)));
    }
}
