package net.exylia.lib.util.editor.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.editor.Clipboard;
import net.exylia.lib.util.editor.EditorDescriptor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Opening list editors, and making sure none of them outlives its owner.
 *
 * <p>The holders are tracked per plugin only so a plugin being disabled can
 * close its screens — never to answer "does this player have an editor open",
 * which is a question the window already answers.
 */
@ApiStatus.Internal
public final class EditorRuntime {

    private static final int SIZE = 54;

    /** Open editors, by owning plugin, so a disable can close them. */
    private static final Set<EditorHolder<?>> OPEN = ConcurrentHashMap.newKeySet();

    /** ExyliaLib itself, for work that has to outlive a consumer. */
    private static volatile Plugin library;

    private EditorRuntime() {
    }

    /** Called once from {@code ExyliaLib.onEnable}. */
    public static void init(@NotNull Plugin plugin) {
        library = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Opens a list editor on the viewer's own thread.
     *
     * @param plugin     the owning plugin
     * @param descriptor how the elements draw and edit
     * @param type       the element type, for the clipboard's per-element check
     * @param title      the window title, in Exylia text notation
     * @param entries    what is being edited; copied, never held
     * @param onSave     told the finished list
     * @param onCancel   told nothing was kept
     * @param viewer     who is editing
     * @param <T>        the element type
     */
    public static <T> void open(@NotNull Plugin plugin,
                                @NotNull EditorDescriptor<T> descriptor,
                                @NotNull Class<T> type,
                                @NotNull String title,
                                @NotNull List<T> entries,
                                @NotNull Consumer<List<T>> onSave,
                                @NotNull Runnable onCancel,
                                @NotNull Player viewer) {
        Tasks.of(scheduler(plugin)).runAtEntity(viewer, () -> {
            EditorHolder<T> holder = new EditorHolder<>(plugin, descriptor, type, title,
                    entries, onSave, onCancel, viewer);
            show(holder, viewer);
        });
    }

    /** Builds the window, draws it and puts it on screen. */
    private static <T> void show(EditorHolder<T> holder, Player viewer) {
        // A legacy title rather than a component: the component overload of
        // createInventory is Paper's and the library must load on Spigot. A
        // title carries colour and nothing else, so nothing is lost.
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Text.from(holder.plugin(), holder.title()).forPlayer(viewer).legacy());
        holder.bind(inventory);
        holder.draw();
        OPEN.add(holder);
        viewer.openInventory(inventory);
    }

    /**
     * Puts an editor back on screen after a question interrupted it.
     *
     * <p>Asking anything means closing the window: a dialog, a chat line and an
     * anvil all need the screen. The editor comes back with the answer already
     * in the list, on the page it was on.
     */
    static <T> void reopen(EditorHolder<T> holder) {
        Player viewer = holder.viewer();
        if (viewer == null || !viewer.isOnline() || holder.isFinished()) {
            forget(holder);
            return;
        }
        Tasks.of(scheduler(holder.plugin())).runAtEntity(viewer, () -> {
            int page = holder.page();
            show(holder, viewer);
            holder.page(page);
            holder.draw();
        });
    }

    /**
     * Closes a window without the close being read as the viewer giving up.
     *
     * <p>Does nothing when the editor is not the window on screen. Adding asks
     * two questions in a row, and the second must not close whatever window the
     * first one opened — an anvil search is an inventory too.
     */
    static void closeForQuestion(EditorHolder<?> holder, Player viewer) {
        if (!OPEN.remove(holder)) {
            return;
        }
        holder.reopening(true);
        try {
            viewer.closeInventory();
        } finally {
            holder.reopening(false);
        }
    }

    static void forget(EditorHolder<?> holder) {
        OPEN.remove(holder);
    }

    /**
     * Asks for a new element, then configures it.
     *
     * <p>Two questions rather than one, because for some types creating the
     * thing is itself a question — a reward has to be told what it gives before
     * a form over it can name its own fields. A type that has nothing to ask
     * answers the first one instantly and the viewer only sees the second.
     */
    static <T> void add(EditorHolder<T> holder, Player viewer) {
        closeForQuestion(holder, viewer);
        created(holder, viewer).whenComplete((created, failure) -> {
            if (failure != null) {
                Debug.of(holder.plugin()).error("An editor could not create an entry for "
                        + viewer.getName() + '.', failure);
            }
            if (failure == null && created != null && created.isPresent()) {
                edit(holder, viewer, created.get(), true);
                return;
            }
            reopen(holder);
        });
    }

    private static <T> java.util.concurrent.CompletionStage<Optional<T>> created(
            EditorHolder<T> holder, Player viewer) {
        try {
            return holder.descriptor().create(viewer);
        } catch (RuntimeException broken) {
            return java.util.concurrent.CompletableFuture.failedFuture(broken);
        }
    }

    /**
     * Runs a descriptor's edit and puts the answer back in the list.
     *
     * <p>The one place the editor hands control to somebody else's code, so it
     * is also the one place that has to survive that code misbehaving: a stage
     * that fails leaves the list untouched and the screen coming back, rather
     * than a window that never reopens.
     */
    static <T> void edit(EditorHolder<T> holder, Player viewer, T entry, boolean isNew) {
        closeForQuestion(holder, viewer);
        Optional<T> nothing = Optional.empty();
        stage(holder, viewer, entry).whenComplete((edited, failure) -> {
            if (failure != null) {
                Debug.of(holder.plugin()).error("An editor could not ask "
                        + viewer.getName() + " about an entry.", failure);
            }
            Optional<T> answer = failure != null ? nothing : edited;
            if (answer != null && answer.isPresent()) {
                if (isNew) {
                    holder.add(answer.get());
                } else {
                    holder.replace(entry, answer.get());
                }
            } else if (isNew) {
                // A new row nobody finished configuring is not a row. Adding it
                // first and removing it here would be the same thing with a
                // flicker; it is simply never added.
                holder.page(holder.page());
            }
            reopen(holder);
        });
    }

    private static <T> java.util.concurrent.CompletionStage<Optional<T>> stage(
            EditorHolder<T> holder, Player viewer, T entry) {
        try {
            return holder.descriptor().edit(viewer, entry);
        } catch (RuntimeException broken) {
            return java.util.concurrent.CompletableFuture.failedFuture(broken);
        }
    }

    /** Closes every editor belonging to one plugin. Called when it disables. */
    public static int release(@NotNull String pluginName) {
        Objects.requireNonNull(pluginName, "pluginName");
        int closed = 0;
        for (EditorHolder<?> holder : List.copyOf(OPEN)) {
            if (holder.plugin().getName().equals(pluginName) && end(holder)) {
                closed++;
            }
        }
        return closed;
    }

    /** Closes every editor. Called on shutdown. */
    public static void releaseAll() {
        for (EditorHolder<?> holder : List.copyOf(OPEN)) {
            end(holder);
        }
        OPEN.clear();
    }

    /** Closes the editors of a player who is leaving, and forgets what they copied. */
    public static void forget(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        for (EditorHolder<?> holder : List.copyOf(OPEN)) {
            if (holder.viewerId().equals(playerId)) {
                end(holder);
            }
        }
        Clipboard.forget(playerId);
    }

    /**
     * Ends one editor without saving.
     *
     * <p>Cancel rather than save, deliberately: a screen taken away by a plugin
     * disabling or a player leaving was never confirmed, and writing a working
     * copy nobody approved is worse than losing it.
     */
    private static boolean end(EditorHolder<?> holder) {
        OPEN.remove(holder);
        if (!holder.finish()) {
            return false;
        }
        Player viewer = holder.viewer();
        if (viewer != null && viewer.isOnline()) {
            viewer.closeInventory();
        }
        return true;
    }

    /** How many editors are open. For tests and {@code /exylialib stats}. */
    public static int open() {
        return OPEN.size();
    }

    /**
     * Whose scheduler does the work.
     *
     * <p>The owning plugin while it is alive, because its editors are its own;
     * ExyliaLib as the fallback, because a disabled plugin cannot schedule the
     * closing of its own screens.
     */
    private static Plugin scheduler(@Nullable Plugin plugin) {
        if (plugin != null && plugin.isEnabled()) {
            return plugin;
        }
        Plugin fallback = library;
        return fallback != null ? fallback : plugin;
    }

    /** Every open editor, for the listener to resolve a window against. */
    static @Nullable EditorHolder<?> holderOf(@Nullable Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        return inventory.getHolder(false) instanceof EditorHolder<?> holder ? holder : null;
    }
}
