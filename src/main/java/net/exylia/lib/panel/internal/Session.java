package net.exylia.lib.panel.internal;

import net.exylia.lib.action.ActionExecution;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.panel.PanelDiff;
import net.exylia.lib.panel.PanelSession;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One open panel, belonging to one player.
 *
 * <p>The authoritative record of what a viewer is editing: the working copy, how
 * far back they can undo, and everything that must be cancelled when the screen
 * goes away. Found through the window's {@link PanelHolder}, never through a map
 * keyed by player — see that class for why.
 *
 * <h2>Threads</h2>
 * The editing half — {@link #undo()}, {@link #diff()}, the working copy — is any
 * thread and touches no Bukkit API. Drawing and closing belong on the thread
 * that owns the viewer, which is where a click handler already is.
 */
@ApiStatus.Internal
public final class Session implements PanelSession {

    private final PanelRuntime runtime;
    private final Player viewer;
    private final WorkingCopy<Map<String, Object>> values;

    /**
     * What must stop when this screen does.
     *
     * <p>A delayed action step, an animation, a pending lookup: without this
     * they outlive the panel and run against a working copy nobody can see.
     */
    private final List<ActionExecution> pending = new CopyOnWriteArrayList<>();

    /** Where a save goes. Called at most once per save, and only when the diff is not empty. */
    private final java.util.function.Consumer<Map<String, Object>> store;

    /** Whether this session is still on screen. Guards release against running twice. */
    private final AtomicBoolean open = new AtomicBoolean(true);

    private final long openedAt;

    private Session(PanelRuntime runtime, Player viewer, Map<String, Object> original,
                    java.util.function.Consumer<Map<String, Object>> store) {
        this.runtime = runtime;
        this.viewer = viewer;
        this.values = WorkingCopy.of(Map.copyOf(original));
        this.store = store;
        this.openedAt = PanelRuntime.now();
        runtime.track(this);
    }

    /**
     * Opens a session over a set of component values.
     *
     * @param runtime  who owns it
     * @param viewer   who is looking
     * @param original what is on disk right now
     * @param store    where a save goes
     */
    static @NotNull Session of(@NotNull PanelRuntime runtime, @NotNull Player viewer,
                               @NotNull Map<String, Object> original,
                               @NotNull java.util.function.Consumer<Map<String, Object>> store) {
        return new Session(runtime, viewer, original, store);
    }

    // ---------------------------------------------------------------- public

    @Override
    public @NotNull Player viewer() {
        return viewer;
    }

    @Override
    public @NotNull Plugin owner() {
        return runtime.plugin();
    }

    @Override
    public boolean undo() {
        return values.undo();
    }

    @Override
    public int undoDepth() {
        return values.undoDepth();
    }

    @Override
    public @NotNull PanelDiff diff() {
        return Diff.between(values.original(), values.current());
    }

    @Override
    public boolean save() {
        boolean wrote = Diff.saveIfChanged(values.original(), values.current(), store);
        close();
        return wrote;
    }

    @Override
    public void cancel() {
        // Everything the viewer did is thrown away before the screen goes, so
        // nothing can be half-persisted by a listener reacting to the close.
        values.cancel();
        close();
    }

    @Override
    public void close() {
        // Closing the window is what releases the session, through the close
        // listener. Released directly as well, because a session created without
        // a window — the sub-panel case — has no close event to wait for.
        viewer.closeInventory();
        release();
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    // -------------------------------------------------------------- internal

    /** The value of one component, as the panel currently holds it. */
    @Nullable Object value(@NotNull String name) {
        return values.current().get(name);
    }

    /**
     * Commits an edit and remembers what it replaced.
     *
     * <p>Any thread: this is map arithmetic, not a draw.
     *
     * @param name  which component
     * @param value its new value
     */
    void edit(@NotNull String name, @Nullable Object value) {
        Map<String, Object> next = new LinkedHashMap<>(values.current());
        next.put(name, value);
        values.edit(Map.copyOf(next));
    }

    /** When this panel was opened, by the module's clock. */
    long openedAt() {
        return openedAt;
    }

    /**
     * Registers something to cancel when this panel closes.
     *
     * <p>Registered after the screen already went away, the execution is
     * cancelled immediately rather than kept: nothing should outlive a panel
     * nobody is looking at, and a button that started work as the window shut is
     * exactly the case that leaks.
     */
    public void cancelOnClose(@NotNull ActionExecution execution) {
        if (!open.get()) {
            execution.cancel("panel closed");
            return;
        }
        pending.add(execution);
    }

    /**
     * Gives back everything this panel took.
     *
     * <p>The single exit. Every way a panel can end — save, cancel, the player
     * closing it, the player leaving, the plugin being disabled — comes through
     * here, so there is one cleanup path rather than one per ending. Claiming
     * the flag atomically is what makes calling it twice harmless.
     */
    public void release() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        for (ActionExecution execution : pending) {
            execution.cancel("panel closed");
        }
        pending.clear();
        values.release();
        runtime.untrack(this);
    }

    /** Plays one of a panel's sounds, if it has one. */
    void play(@Nullable String sound) {
        if (sound == null || sound.isBlank()) {
            return;
        }
        Effects.soundFrom(sound).show(viewer);
    }

    // ------------------------------------------------------------ test seam

    /**
     * Test seam: a session with no window and nothing to save to.
     *
     * <p>Opening a real one needs {@code Bukkit.createInventory}, which returns
     * nothing without a running server. What the lifecycle tests are about is
     * release — who gets closed, what gets cancelled, and in what order — and
     * none of that is about drawing.
     *
     * @param runtime who owns it
     * @param viewer  who is looking
     * @param name    a component, so the session is not vacuously empty
     */
    public static @NotNull Session forTests(@NotNull PanelRuntime runtime, @NotNull Player viewer,
                                            @NotNull String name) {
        return of(runtime, viewer, Map.of(name, ""), values -> {
        });
    }
}
