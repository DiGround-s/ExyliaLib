package net.exylia.lib.panel.internal;

import net.exylia.lib.action.ActionExecution;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.panel.PanelDiff;
import net.exylia.lib.panel.PanelSession;
import net.exylia.lib.ui.ClickKind;
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

    /**
     * What a click in this window is handed to.
     *
     * <p>Set once, when the window is bound. A session created for a test has
     * none, which is why this is nullable rather than final: the lifecycle tests
     * are about release, and release does not draw.
     */
    private @Nullable Clicks clicks;

    /** The window, or {@code null} for a session that was never drawn. */
    private @Nullable org.bukkit.inventory.Inventory window;

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

    /**
     * What a panel does with a click.
     *
     * <p>An interface rather than a field of engine code, so the settings panel
     * and the list panel each answer their own clicks without the session
     * knowing which it is holding. The session's job is to say <em>whose</em>
     * click it is; what the click means belongs to whoever drew the slot.
     */
    public interface Clicks {

        /**
         * Acts on a click in a slot of this panel's window.
         *
         * <p>Runs on the thread that owns the viewer, which is where the
         * inventory event already is.
         *
         * @param slot the raw slot, as the client reported it
         * @return whether the click meant anything
         */
        boolean activate(int slot);
    }

    /**
     * Binds this session to the window it is drawn in.
     *
     * <p>Called once, as the panel opens. Until it happens the session exists
     * but nothing can find it, which is exactly right: a click cannot arrive in
     * a window that has not been shown.
     */
    void bind(@NotNull org.bukkit.inventory.Inventory window, @NotNull Clicks clicks) {
        this.window = window;
        this.clicks = clicks;
    }

    /** The window this panel is drawn in, or {@code null} before it is bound. */
    public @Nullable org.bukkit.inventory.Inventory window() {
        return window;
    }

    /**
     * Hands a click to whoever drew the slot.
     *
     * <p>Answers {@code false} for a session with nothing bound, so a click that
     * arrives between opening and drawing is ignored rather than thrown.
     */
    public boolean click(int slot) {
        return clicks != null && open.get() && clicks.activate(slot);
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

    /** Every component value the panel is holding, as one map. */
    @NotNull Map<String, Object> values() {
        return values.current();
    }

    /**
     * What the panel opened with.
     *
     * <p>What a save would be measured against, and what a cancel restores. A
     * list panel needs it to answer a richer question than {@link #diff()} can —
     * which <em>entries</em> were added, removed and changed, rather than which
     * components — and it must be the same "before" the save path uses, or the
     * diff shown to a viewer describes a different write than the one performed.
     */
    @NotNull Map<String, Object> originalValues() {
        return values.original();
    }

    /**
     * Replaces the whole set of component values, remembering what it replaced.
     *
     * <p>For an edit that changes more than one component at once — a nested
     * record rebuilt into its parent changes exactly one, but the frame it was
     * rebuilt through may sit several levels down. One snapshot per player
     * action, rather than one per level, is what makes undo take back what they
     * think they did.
     */
    void editAll(@NotNull Map<String, Object> replacement) {
        values.edit(Map.copyOf(replacement));
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
        // Dropped explicitly rather than left to the session going out of scope:
        // whoever holds a PanelSession holds this too, and a copied entry is
        // exactly the kind of thing that keeps a whole graph alive.
        clipboard = null;
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

    // ------------------------------------------------------- list panels

    /**
     * What the panel on this screen has copied, if anything.
     *
     * <p>On the session and nowhere else. The alternative — a
     * {@code static Map<UUID, Object>} — outlives the panel, the player and the
     * plugin, so a copy made an hour ago still pins an object nobody can reach.
     * One field of one session cannot do that: it is reachable only through the
     * window it belongs to, and that window is gone.
     */
    private volatile @Nullable Object clipboard;

    /**
     * The kind of the click currently being handled.
     *
     * <p>An inventory event carries it and the routing does not, because most
     * panels do not care: a settings control means the same thing however it was
     * clicked. A list row does not — right is delete and shift-left is copy —
     * so the kind is recorded as the event passes and read by whoever answers
     * the click.
     *
     * <p>Defaults to {@link ClickKind#LEFT}, which is what a click of a kind
     * nothing binds should be treated as rather than dropped.
     */
    private volatile @NotNull ClickKind observed = ClickKind.LEFT;

    /** Remembers what kind of click is being routed. Called before {@link #click(int)}. */
    void observed(@Nullable ClickKind kind) {
        this.observed = kind == null ? ClickKind.LEFT : kind;
    }

    /** The kind of the click being handled right now. */
    public @NotNull ClickKind observedClick() {
        return observed;
    }

    /**
     * Puts something on this screen's clipboard.
     *
     * <p>Refused once the screen has gone: a panel nobody is looking at must not
     * start holding references.
     */
    void clipboard(@Nullable Object value) {
        this.clipboard = open.get() ? value : null;
    }

    /**
     * What this screen has copied, or {@code null}.
     *
     * <p>A released session holds nothing, and says so by dropping the reference
     * as it answers rather than merely reporting empty.
     */
    @Nullable Object clipboard() {
        if (!open.get()) {
            clipboard = null;
        }
        return clipboard;
    }

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
