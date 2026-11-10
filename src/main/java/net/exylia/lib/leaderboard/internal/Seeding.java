package net.exylia.lib.leaderboard.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * Offline players waiting to be read into a new board, handed out a few at a
 * time: the next batch only once every read of the last one finished, so a
 * slow database or disk slows the seeding down instead of piling reads up.
 *
 * @param <T> what is read: an offline player
 * @since 1.563.0
 */
public final class Seeding<T> {

    private final Deque<T> waiting;
    private final int batch;
    private int reading;
    private int read;

    public Seeding(Collection<T> everyone, int batch) {
        this.waiting = new ArrayDeque<>(everyone);
        this.batch = Math.max(1, batch);
    }

    /** The next batch to read, or nothing while the last one is still being read. */
    public synchronized List<T> next() {
        if (reading > 0) return List.of();
        List<T> next = new ArrayList<>(Math.min(batch, waiting.size()));
        while (next.size() < batch && !waiting.isEmpty()) next.add(waiting.poll());
        reading = next.size();
        return next;
    }

    /** One read of the current batch finished, whatever it found. */
    public synchronized void done() {
        if (reading > 0) {
            reading--;
            read++;
        }
    }

    /** Whether everybody was read. */
    public synchronized boolean finished() {
        return waiting.isEmpty() && reading == 0;
    }

    /** How many reads finished. */
    public synchronized int read() {
        return read;
    }
}
