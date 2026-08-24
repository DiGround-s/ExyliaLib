package net.exylia.lib.panel;

import net.exylia.lib.panel.internal.ListEngine;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * A paginated list editor for any element type, from a {@link FieldDescriptor}.
 *
 * <pre>{@code
 * Panels.of(this).list(new WarpDescriptor(store)).open(player);
 *
 * // with a title and something to do afterwards
 * Panels.of(this).list(new WarpDescriptor(store))
 *         .title("{primary}&lWARPS")
 *         .onSaved(warps -> reloadSigns(warps))
 *         .open(player);
 * }</pre>
 *
 * <h2>What the viewer gets</h2>
 * Pagination, search, add, copy, paste, edit, delete, undo, save and cancel —
 * over a type this library has never heard of, from one interface
 * implementation. ExyliaCommons needed a whole editor per element type and had
 * five of them, drifting apart; this is the one implementation they should have
 * been.
 *
 * <h2>Rows are addressed by what they carry</h2>
 * Every operation resolves its target from the element the clicked row carries,
 * never from a slot, a page or a list index. That is what keeps a delete correct
 * when the list is filtered, paginated, or changed by something else while the
 * panel is open — the bug Commons' potion editor had, where
 * {@code potion_delete 1} removed whatever happened to be at index one.
 *
 * <h2>Nothing is written until save</h2>
 * Every operation lands in a working copy. {@link PanelSession#cancel()}, closing
 * the window, quitting, and the owning plugin being disabled all discard the
 * whole of it — deletions, pastes and edits alike, never some of them. A save
 * whose {@link PanelSession#diff()} is empty writes nothing at all: opening a
 * list to look at it must not rewrite the owner's file.
 *
 * <p>A save is the only thing that calls {@link FieldDescriptor#save}, and it
 * calls it once, off the viewer's thread, with the whole list.
 *
 * <h2>Threads</h2>
 * {@link #open(Player)} is safe from <b>any thread</b> and relocates itself onto
 * the thread that owns the player. The write is asynchronous and returns to that
 * thread before {@link #onSaved(Consumer)} runs. Behaviour is identical on
 * Spigot, Paper, Purpur and Folia.
 *
 * <h2>Lifecycle</h2>
 * Reusable: {@link #open(Player)} may be called for several players, and each
 * gets their own session, working copy, undo history and clipboard. Nothing —
 * including the clipboard — survives the screen it belongs to.
 *
 * @param <T> the element type being edited
 * @since 1.50.0
 */
public final class ListPanel<T> {

    private final Plugin plugin;
    private final FieldDescriptor<T> descriptor;

    private @Nullable String title;
    private @Nullable Consumer<List<T>> onSaved;

    @ApiStatus.Internal
    ListPanel(@NotNull Plugin plugin, @NotNull FieldDescriptor<T> descriptor) {
        this.plugin = plugin;
        this.descriptor = descriptor;
    }

    /**
     * Sets the window title.
     *
     * <p>Written in Exylia text notation, so {@code {primary}} rather than a hex
     * value: an owner who rethemes the palette rethemes this too.
     *
     * @param title the title
     * @return this panel, for chaining
     */
    public @NotNull ListPanel<T> title(@NotNull String title) {
        this.title = title;
        return this;
    }

    /**
     * Runs an action after a successful write.
     *
     * <p>Given the list that was persisted, on the thread that owns the viewer —
     * so it is safe to touch the game from it. Not run when nothing changed,
     * because nothing was written.
     *
     * @param action what to do with the new list
     * @return this panel, for chaining
     */
    public @NotNull ListPanel<T> onSaved(@NotNull Consumer<List<T>> action) {
        this.onSaved = action;
        return this;
    }

    /**
     * Shows the panel to a player.
     *
     * <p>Safe to call from any thread: it relocates itself onto the thread that
     * owns the player, exactly as {@code Menus} does. There is deliberately no
     * {@code openNow} — nothing about a panel needs a session synchronously, and
     * offering one would export a thread precondition into an API whose whole
     * point is that there is nothing to get right.
     *
     * <p>{@link FieldDescriptor#load()} is called once, here, as the panel opens.
     * What it returns is copied immediately, so the panel is not affected by
     * anything that happens to the store afterwards.
     *
     * @param viewer who to show it to
     */
    public void open(@NotNull Player viewer) {
        String chosen = title;
        Consumer<List<T>> notify = onSaved;
        Tasks.of(plugin).runAtEntity(viewer,
                () -> ListEngine.of(plugin, viewer, descriptor, notify).open(chosen));
    }
}
