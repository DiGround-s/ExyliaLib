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
     * A screen for editing a list of real items.
     *
     * <p>Kits, shop stock, the contents of a crate. Adding and editing both open
     * the one-slot window, so the whole item is kept: stack size, model,
     * enchantments and all.
     *
     * @param items what is being edited; copied, never held
     * @return the editor, ready to open
     */
    public @NotNull ListEditor<org.bukkit.inventory.ItemStack> items(
            @NotNull List<org.bukkit.inventory.ItemStack> items) {
        return list(new ItemListEditor(plugin), org.bukkit.inventory.ItemStack.class, items);
    }

    /**
     * A screen for editing a list of places.
     *
     * <p>Spawn points, arena corners, warp targets. Adding a row takes the
     * viewer's own position, and so does editing one: nobody types coordinates.
     *
     * @param locations what is being edited; copied, never held
     * @return the editor, ready to open
     */
    public @NotNull ListEditor<net.exylia.lib.util.teleport.ExyliaLocation> locations(
            @NotNull List<net.exylia.lib.util.teleport.ExyliaLocation> locations) {
        return list(new LocationDescriptor(plugin),
                net.exylia.lib.util.teleport.ExyliaLocation.class, locations);
    }

    /**
     * Choosing one thing out of a list the server owns.
     *
     * <pre>{@code
     * editors.pick().particle(player)
     *        .thenAccept(name -> name.ifPresent(entry::setParticle));
     * }</pre>
     *
     * @return the pickers
     */
    public @NotNull Pickers pick() {
        return new Pickers(plugin);
    }

}
