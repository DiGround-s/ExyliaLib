package net.exylia.lib.panel.internal;

import net.exylia.lib.panel.Panels;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What a panel is editing, and how far back it can go.
 *
 * <p>A panel never mutates persisted state. It edits this, and only a save
 * writes it out — which is what makes cancel free and undo possible at all.
 *
 * <h2>Why the bound is cheap</h2>
 * A snapshot is a <em>reference</em>, not a deep copy. Records are immutable, so
 * the previous value is the instance the next edit would have discarded anyway;
 * a list snapshot is a {@code List.copyOf}, whose elements are shared because
 * they too are immutable. The realistic worst case — twenty snapshots of a
 * five-hundred entry reward list — is about eighty kilobytes for as long as the
 * panel is open, and it is released on close.
 *
 * <h2>Threads</h2>
 * Any thread. There is no Bukkit API here at all, which is what lets a panel
 * compute a diff or take back an edit off the viewer's thread. Instances are not
 * shared between viewers: one session owns one of these.
 *
 * @param <T> what is being edited
 */
@ApiStatus.Internal
public final class WorkingCopy<T> {

    /** What was there when the panel opened. Never discarded: cancel and diff need it. */
    private final T original;

    /**
     * Previous values, newest last.
     *
     * <p>A deque rather than a list because the two operations are "push newest"
     * and "drop oldest", and this does both in constant time.
     */
    private final Deque<T> history = new ArrayDeque<>();

    private T current;

    private WorkingCopy(T original) {
        this.original = original;
        this.current = original;
    }

    /** Starts editing a value. */
    public static <T> @NotNull WorkingCopy<T> of(@NotNull T original) {
        return new WorkingCopy<>(original);
    }

    /** What was on disk when the panel opened. */
    public @NotNull T original() {
        return original;
    }

    /** What the panel is showing now. */
    public @NotNull T current() {
        return current;
    }

    /**
     * Commits an edit, remembering what it replaced.
     *
     * <p>Overflow discards the oldest snapshot rather than throwing: somebody
     * making their twenty-first edit is doing normal work, and refusing it — or
     * worse, failing — would be a bug dressed as a limit.
     *
     * @param value the new value
     */
    public void edit(@NotNull T value) {
        if (history.size() >= Panels.undoLimit()) {
            history.removeFirst();
        }
        history.addLast(current);
        current = value;
    }

    /**
     * Takes back the last committed edit.
     *
     * @return whether there was anything to take back
     */
    public boolean undo() {
        if (history.isEmpty()) {
            return false;
        }
        current = history.removeLast();
        return true;
    }

    /** How many edits can still be taken back. */
    public int undoDepth() {
        return history.size();
    }

    /** Throws every edit away, as cancel does. */
    public void cancel() {
        current = original;
        history.clear();
    }

    /** Drops the history. Called when the panel closes, so nothing outlives it. */
    public void release() {
        history.clear();
    }
}
