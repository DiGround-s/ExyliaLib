package net.exylia.lib.util.crate.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One column of the opening screen, mid-fall.
 *
 * <p>The prize is known from the first frame — the key is already spent — but
 * the outcome is not: whether it is a win or a duplicate is decided by the
 * payout, and the payout is the moment the reel stops. Until then
 * {@link #outcome()} is {@code null} and the column is just faces going past.
 *
 * @param <T> the plugin's reward type
 */
public final class Reel<T> {

    /** How tall a reel is: the full height of the chest. */
    public static final int ROWS = 6;

    /** The row a prize lands on, counted from the top. */
    public static final int WINNER_ROW = 2;

    /** The columns reels may use, which is every one but the two at the edges. */
    public static final int FIRST_COLUMN = 1;
    public static final int LAST_COLUMN = 7;
    public static final int COLUMNS = LAST_COLUMN - FIRST_COLUMN + 1;

    /** As long as a reel can possibly run, whatever the settings ask for. */
    public static final int MAX_FRAMES = 200;

    private final int column;
    private final List<T> strip;
    private final long[] at;
    private final T prize;

    /** What the payout said, or {@code null} while the reel is still falling. */
    private volatile @Nullable Prizes.Outcome<T> outcome;

    /** Whether this reel is spoken for: paid out, or given up on. */
    private boolean claimed;

    public Reel(int column, @NotNull List<T> strip, long[] at, @NotNull T prize) {
        this.column = column;
        this.strip = strip;
        this.at = at;
        this.prize = prize;
    }

    /**
     * Builds a reel whose prize lands on {@link #WINNER_ROW} after {@code frames} faces.
     *
     * <p>The times are worked out once rather than a frame at a time, because
     * reels do not share a clock: each has its own finishing line, and each has
     * to be slowing down as it reaches its own rather than the last one's.
     *
     * @param faces what the other cells show, asked once per cell
     */
    public static <T> @NotNull Reel<T> of(int column, @NotNull T prize, int frames, @NotNull Supplier<@Nullable T> faces) {
        List<T> strip = new ArrayList<>(frames + ROWS);
        for (int i = 0; i < frames + ROWS; i++) {
            T face = faces.get();
            strip.add(face != null ? face : prize);
        }
        strip.set(frames + WINNER_ROW, prize);

        long[] at = new long[frames + 1];
        for (int frame = 1; frame <= frames; frame++) {
            at[frame] = at[frame - 1] + delay(frames - frame + 1);
        }
        return new Reel<>(column, strip, at, prize);
    }

    /**
     * How long a face stays before the next one, in ticks.
     *
     * <p>One tick while there is a way to go, then a widening gap over the last
     * eight: the eye reads the slowdown as the reel losing momentum rather than
     * as the server lagging.
     */
    public static long delay(int framesLeft) {
        return switch (framesLeft) {
            case 1 -> 9;
            case 2 -> 7;
            case 3 -> 5;
            case 4 -> 4;
            case 5, 6 -> 3;
            case 7, 8 -> 2;
            default -> 1;
        };
    }

    /**
     * Which columns the reels sit in, always centred.
     *
     * <p>Up to four are spread with a gap between them, which is what makes four
     * reels read as four rather than as one wide block. More than that no longer
     * fits with gaps, so they close up instead.
     */
    public static @NotNull List<Integer> columns(int count) {
        int centre = FIRST_COLUMN + (COLUMNS - 1) / 2;
        List<Integer> columns = new ArrayList<>(count);
        if (count <= (COLUMNS + 1) / 2) {
            int first = centre - (count - 1);
            for (int i = 0; i < count; i++) {
                columns.add(first + i * 2);
            }
            return columns;
        }
        int first = Math.max(FIRST_COLUMN, centre - (count - 1) / 2);
        for (int i = 0; i < count; i++) {
            columns.add(first + i);
        }
        return columns;
    }

    public int column() {
        return column;
    }

    public @NotNull T prize() {
        return prize;
    }

    public @Nullable Prizes.Outcome<T> outcome() {
        return outcome;
    }

    /**
     * Pays this reel out, once.
     *
     * <p>Synchronised against {@link #abandon()}: a player quitting is another
     * path arriving at the same reel, and a prize handed over twice is worse
     * than either.
     *
     * @param payout what hands the prize over
     * @return the outcome if this call settled it, {@code null} if it was already
     *         settled or given up on
     */
    public @Nullable Prizes.Outcome<T> settle(@NotNull Function<T, Prizes.Outcome<T>> payout) {
        synchronized (this) {
            if (claimed) return null;
            claimed = true;
        }
        Prizes.Outcome<T> settled = payout.apply(prize);
        outcome = settled;
        return settled;
    }

    /**
     * Gives up on this reel, so it can be paid out some other way.
     *
     * @return whether there was still a prize on it to give up
     */
    public boolean abandon() {
        synchronized (this) {
            if (claimed) return false;
            claimed = true;
            return true;
        }
    }

    public int frames() {
        return at.length - 1;
    }

    public boolean landed(long elapsed) {
        return elapsed >= at[frames()];
    }

    /**
     * How many faces have gone past by now.
     *
     * <p>Read from the clock rather than counted frame by frame, so a server that
     * skipped a tick shows the reel where it should be instead of running slow.
     */
    public int frameAt(long elapsed) {
        int low = 0;
        int high = frames();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (at[middle] <= elapsed) low = middle; else high = middle - 1;
        }
        return low;
    }

    /** The face in one row of this column right now. */
    public @Nullable T faceAt(long elapsed, int row) {
        int index = frameAt(elapsed) + row;
        return index < strip.size() ? strip.get(index) : null;
    }
}
