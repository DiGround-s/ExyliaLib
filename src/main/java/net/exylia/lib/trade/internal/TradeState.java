package net.exylia.lib.trade.internal;

/**
 * The rules of one trade, with no server attached: who confirmed, whether the
 * countdown runs, and which side has been taken out of its window.
 *
 * <p>Sides are {@code 0} and {@code 1}. Every method is synchronized: the two
 * windows answer clicks on their own players' threads, and the countdown is
 * checked on the global one.
 *
 * <h2>A confirm counts what was on screen</h2>
 * Every change to a side's offer bumps that side's revision before it lands,
 * and resets both confirmations. A side may confirm only when its own latest
 * snapshot and the copy of the other side it is looking at are both current,
 * so nobody confirms a stake the other player swapped a tick earlier.
 */
public final class TradeState {

    /** Where the trade is. */
    public enum Phase {
        /** Offers can change; nobody, or one side, has confirmed. */
        OPEN,
        /** Both confirmed; any change goes back to {@link #OPEN}. */
        COUNTDOWN,
        /** Agreed: the windows are being emptied into the result. */
        COMPLETING,
        /** Called off: the windows are being emptied back to their owners. */
        CANCELLED
    }

    /** What a press of the confirm button did. */
    public enum Toggle {
        CONFIRMED,
        UNCONFIRMED,
        /** A change has not reached a screen yet; nothing moved. */
        STALE,
        /** The side offers nothing and the trade does not allow that. */
        EMPTY,
        /** The trade is no longer open. */
        LOCKED
    }

    private final long countdownMillis;
    private final boolean allowEmpty;
    private Phase phase = Phase.OPEN;
    private final int[] revision = new int[2];
    private final int[] snapped = new int[2];
    private final boolean[] empty = {true, true};
    private final int[] seen = new int[2];
    private final boolean[] confirmed = new boolean[2];
    private final boolean[] taken = new boolean[2];
    private final boolean[] handed = new boolean[2];
    private long deadline;

    public TradeState(long countdownMillis, boolean allowEmpty) {
        this.countdownMillis = Math.max(0, countdownMillis);
        this.allowEmpty = allowEmpty;
    }

    /**
     * A side's offer is about to change. Refused once the trade is no longer
     * open; otherwise both confirmations and any countdown are reset.
     */
    public synchronized boolean change(int side) {
        if (!editable()) {
            return false;
        }
        revision[side]++;
        confirmed[0] = false;
        confirmed[1] = false;
        phase = Phase.OPEN;
        return true;
    }

    /** A side's snapshot now reflects its offer at {@code rev}. */
    public synchronized void snapped(int side, int rev, boolean isEmpty) {
        if (rev >= snapped[side]) {
            snapped[side] = rev;
            empty[side] = isEmpty;
        }
    }

    /** A side is now looking at the other side's offer at {@code otherRevision}. */
    public synchronized void shown(int viewer, int otherRevision) {
        seen[viewer] = Math.max(seen[viewer], otherRevision);
    }

    /** A press of a side's confirm button. */
    public synchronized Toggle toggle(int side, long now) {
        if (!editable()) {
            return Toggle.LOCKED;
        }
        if (confirmed[side]) {
            confirmed[side] = false;
            phase = Phase.OPEN;
            return Toggle.UNCONFIRMED;
        }
        if (snapped[side] != revision[side] || seen[side] != revision[1 - side]) {
            return Toggle.STALE;
        }
        if (empty[side] && !allowEmpty) {
            return Toggle.EMPTY;
        }
        confirmed[side] = true;
        if (confirmed[1 - side]) {
            phase = countdownMillis == 0 ? Phase.COMPLETING : Phase.COUNTDOWN;
            deadline = now + countdownMillis;
        }
        return Toggle.CONFIRMED;
    }

    /** Ends a countdown that ran out; {@code true} exactly once, when it moves to completing. */
    public synchronized boolean tick(long now) {
        if (phase == Phase.COUNTDOWN && now >= deadline) {
            phase = Phase.COMPLETING;
            return true;
        }
        return false;
    }

    /** Calls the trade off; {@code true} only for the call that did it. */
    public synchronized boolean cancel() {
        if (!editable()) {
            return false;
        }
        phase = Phase.CANCELLED;
        return true;
    }

    /** Claims a side's window to be emptied; {@code true} once per side, and only once the trade has ended. */
    public synchronized boolean take(int side) {
        if (editable() || taken[side]) {
            return false;
        }
        taken[side] = true;
        return true;
    }

    /** A side's items have been handed over; {@code true} for whichever side was last. */
    public synchronized boolean handed(int side) {
        handed[side] = true;
        return handed[0] && handed[1];
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean editable() {
        return phase == Phase.OPEN || phase == Phase.COUNTDOWN;
    }

    public synchronized boolean confirmed(int side) {
        return confirmed[side];
    }

    public synchronized int revision(int side) {
        return revision[side];
    }

    /** Milliseconds left on the countdown; {@code 0} when none runs. */
    public synchronized long remaining(long now) {
        return phase == Phase.COUNTDOWN ? Math.max(0, deadline - now) : 0;
    }
}
