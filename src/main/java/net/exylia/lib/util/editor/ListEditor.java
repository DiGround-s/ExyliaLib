package net.exylia.lib.util.editor;

import net.exylia.lib.util.editor.internal.EditorRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A paginated editor for a list of anything.
 *
 * <pre>{@code
 * Editors.of(this).list(new WarpDescriptor(), Warp.class, warps)
 *         .title("{primary}&lWARPS")
 *         .onSave(edited -> store.save(edited))
 *         .onCancel(() -> mainMenu.open(player))
 *         .open(player);
 * }</pre>
 *
 * <p>The viewer gets pagination, add, edit, delete, copy, paste, save and
 * cancel. No screen, session, holder or clipboard is written for a new element
 * type: that is what {@link EditorDescriptor} is.
 *
 * <h2>Nothing is written until save</h2>
 * The list handed in is copied, never held. Every change goes into that copy, so
 * cancel is free and an editor opened to look at something writes nothing at
 * all. {@code onSave} is told the finished list exactly once.
 *
 * <h2>Every ending goes through one door</h2>
 * Save, cancel, closing the window, leaving the server and the owning plugin
 * being disabled are five ways out, and the first one wins. Four of them are
 * cancel: a screen taken away was never confirmed, and writing a working copy
 * nobody approved is worse than losing it.
 *
 * @param <T> what is being edited
 * @since 1.56.0
 */
public final class ListEditor<T> {

    private final Plugin plugin;
    private final EditorDescriptor<T> descriptor;
    private final Class<T> type;
    private final List<T> entries;

    private String title = "{primary}&lEDITOR";
    private Consumer<List<T>> onSave = edited -> { };
    private Runnable onCancel = () -> { };

    ListEditor(Plugin plugin, EditorDescriptor<T> descriptor, Class<T> type, List<T> entries) {
        this.plugin = plugin;
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.type = Objects.requireNonNull(type, "type");
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * The window title, in Exylia text notation.
     *
     * @param title the title
     * @return this editor
     */
    public @NotNull ListEditor<T> title(@NotNull String title) {
        this.title = Objects.requireNonNull(title, "title");
        return this;
    }

    /**
     * What to do with the finished list.
     *
     * <p>Called once, on the viewer's own thread, only when they pressed save.
     *
     * @param onSave told the edited list
     * @return this editor
     */
    public @NotNull ListEditor<T> onSave(@NotNull Consumer<List<T>> onSave) {
        this.onSave = Objects.requireNonNull(onSave, "onSave");
        return this;
    }

    /**
     * What to do when nothing was kept.
     *
     * <p>Normally reopening the screen the editor was entered from. Called for
     * every ending that is not a save, including the plugin being disabled.
     *
     * @param onCancel told the editor closed with nothing
     * @return this editor
     */
    public @NotNull ListEditor<T> onCancel(@NotNull Runnable onCancel) {
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
        return this;
    }

    /**
     * Puts the editor on screen.
     *
     * <p>Safe from any thread: it relocates itself onto the thread that owns the
     * viewer. There is deliberately no {@code openNow} — nothing about an editor
     * needs a window synchronously, and offering one would export a thread
     * precondition into an API whose point is that there is nothing to get right.
     *
     * @param viewer who is editing
     */
    public void open(@NotNull Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        EditorRuntime.open(plugin, descriptor, type, title, entries, onSave, onCancel, viewer);
    }
}
