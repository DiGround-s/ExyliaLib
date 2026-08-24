package net.exylia.lib.util.editor;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * One plugin's editors.
 *
 * <p>Held in a field and reused; there is nothing to release from here, because
 * an editor belongs to the window it is drawn in and ExyliaLib closes those when
 * the plugin goes away.
 *
 * @since 1.56.0
 */
public final class PluginEditors {

    private final Plugin plugin;

    PluginEditors(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** The plugin these editors belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * A paginated editor over a list of anything.
     *
     * @param descriptor how the elements draw and edit
     * @param type       the element type
     * @param entries    what is being edited; copied, never held
     * @param <T>        the element type
     * @return the editor, ready to open
     */
    public @NotNull <T> ListEditor<T> list(@NotNull EditorDescriptor<T> descriptor,
                                           @NotNull Class<T> type,
                                           @NotNull List<T> entries) {
        return new ListEditor<>(plugin, descriptor, type, entries);
    }

    /**
     * Asks what something should be drawn as.
     *
     * <pre>{@code
     * editors.icon().open(player, icon -> arenas.save(arena.withIcon(icon)));
     * }</pre>
     *
     * @return the picker, ready to open
     */
    public @NotNull IconPicker icon() {
        return new IconPicker(plugin);
    }
}
