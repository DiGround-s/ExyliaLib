package net.exylia.lib.spatial.internal;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * The steps a spatial menu's UNDO walks back through: the state before each click that changed it,
 * newest on top, at most {@code limit} of them (the oldest goes first). A click that changed nothing
 * is not a step.
 *
 * <p>Internal: free to change without notice.
 */
public final class UndoHistory<T> {

    private final Deque<T> steps = new ArrayDeque<>();
    private final int limit;

    public UndoHistory(int limit) {
        this.limit = Math.max(1, limit);
    }

    /** Remembers {@code before} as a step when the click turned it into something else. */
    public void record(T before, T after) {
        if (Objects.equals(before, after)) return;
        steps.push(before);
        while (steps.size() > limit) steps.removeLast();
    }

    /** The state before the newest step, taken off the history; {@code null} when there is none. */
    public @Nullable T pop() {
        return steps.pollFirst();
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int size() {
        return steps.size();
    }

    public void clear() {
        steps.clear();
    }
}
