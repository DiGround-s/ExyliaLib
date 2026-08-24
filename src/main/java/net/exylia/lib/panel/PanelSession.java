package net.exylia.lib.panel;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * One open panel, belonging to one player.
 *
 * <p>A panel edits a <em>working copy</em>. Nothing a viewer does reaches the
 * config file until {@link #save()}, which is what makes {@link #cancel()} free
 * and {@link #undo()} possible at all: taking back an edit is taking back a
 * value in memory, not rewriting a file.
 *
 * <p>A session is found through the window it is bound to, not through a map
 * keyed by player. That is deliberate and it is the difference between a click
 * being safe and not: a player with a chest open on top of a panel is looking at
 * the chest, and {@link Panels#session(Player)} says so.
 *
 * <h2>Threads</h2>
 * {@link #undo()}, {@link #undoDepth()} and {@link #diff()} are safe from any
 * thread and touch no Bukkit API — they are arithmetic over the working copy.
 * {@link #save()}, {@link #cancel()} and {@link #close()} end the screen, so
 * they belong on the thread that owns the viewer, which is where a click
 * handler already is.
 *
 * <h2>Lifecycle</h2>
 * A session ends when the viewer saves, cancels, closes the window, leaves the
 * server, or the owning plugin is disabled. All five give back everything the
 * panel took, including any delayed action step a button started. After that
 * {@link #isOpen()} is {@code false} and the session holds nothing.
 *
 * @since 1.50.0
 */
public interface PanelSession {

    /** Who is looking at it. */
    @NotNull Player viewer();

    /** Whose panel it is. */
    @NotNull Plugin owner();

    /**
     * Takes back the last committed edit.
     *
     * <p>Bounded at {@link Panels#undoLimit()} snapshots; past that the oldest
     * is discarded rather than an exception being raised, because somebody
     * making their twenty-first edit is doing normal work.
     *
     * @return {@code true} when an edit was taken back, {@code false} when there
     *         was nothing left to undo — a no-op, never an error
     */
    boolean undo();

    /**
     * How many edits can still be taken back.
     *
     * @return between zero and {@link Panels#undoLimit()}
     */
    int undoDepth();

    /**
     * What this panel would change if it saved right now.
     *
     * <p>Cheap and side-effect free, so it is safe to ask before showing a
     * confirmation.
     *
     * @return the difference, never {@code null}; possibly {@link PanelDiff#isEmpty() empty}
     */
    @NotNull PanelDiff diff();

    /**
     * Writes the working copy back and closes the panel.
     *
     * <p>An empty {@link #diff()} writes nothing at all: opening a panel to look
     * at it must not rewrite the owner's file.
     *
     * @return whether anything was actually written
     */
    boolean save();

    /**
     * Throws the working copy away and closes the panel.
     *
     * <p>Nothing is partially persisted: what is on disk is what was there when
     * the panel opened.
     */
    void cancel();

    /** Closes the panel, releasing everything it holds. */
    void close();

    /** Whether this panel is still the one on screen. */
    boolean isOpen();
}
