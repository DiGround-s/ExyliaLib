package net.exylia.lib.replay;

import org.jetbrains.annotations.NotNull;

/**
 * What the black box holds and costs right now, for a diagnostics command.
 *
 * <p>Sizes are worked out from what is held, not measured on the heap: close
 * enough to tell a box that fits from one that does not, not a profiler.
 *
 * @param running        whether a black box is open at all; every other figure
 *                       is zero when it is not
 * @param players        tapes of players
 * @param entities       tapes of everything else, of {@code maxEntities} at most
 * @param frames         frames across every tape
 * @param tapeBytes      roughly what the tapes take on the heap
 * @param marks          marks kept: pings, chat, hits, explosions and the rest
 * @param markBytes      roughly what they take
 * @param changes        block changes kept
 * @param changeBytes    roughly what they take
 * @param capped         how many times in the last second a look around a player
 *                       found something it left out because the box was full;
 *                       one thing near two players counts twice
 * @param samplingNanos  time spent sampling in the last second, summed over every
 *                       thread that did it: the server thread on Paper, the
 *                       regions on Folia
 * @since 1.400.0
 */
public record BlackBoxStats(boolean running, int players, int entities, long frames, long tapeBytes,
                            int marks, long markBytes, int changes, long changeBytes,
                            int capped, long samplingNanos) {

    /** No black box open. */
    public static @NotNull BlackBoxStats idle() {
        return new BlackBoxStats(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Everything it holds, roughly, in bytes. */
    public long bytes() {
        return tapeBytes + markBytes + changeBytes;
    }
}
