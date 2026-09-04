package net.exylia.lib.util.editor;

import net.exylia.lib.util.editor.internal.EditorRuntime;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An inventory-shaped editor for a loadout.
 *
 * <pre>{@code
 * Editors.of(this).loadout(kit.items())
 *         .title("{primary}&lKIT ITEMS {letters_black}» {highlight}" + kit.id())
 *         .onSave(items -> kits.save(kit.withItems(items)))
 *         .onCancel(() -> KitMenu.open(player))
 *         .open(player);
 * }</pre>
 *
 * <p>The admin sees their own inventory: armour and offhand in the corner, the
 * three storage rows and the hotbar under them. They put real items in real
 * slots, so armour stays in the armour slots and the hotbar stays the hotbar,
 * and the slots themselves are the record — nothing is paginated and nothing is
 * retyped. {@link Loadout} says which position each slot is.
 *
 * <p>What this replaces is a screen of get/set buttons — ExyliaPracticeCore
 * asked an admin to arrange their own inventory and then type {@code done} into
 * chat, which cannot show what the kit already holds and cannot be corrected one
 * slot at a time.
 *
 * <h2>Closing saves</h2>
 * The other editors in this module treat a close as walking away, because their
 * working copy is a list nobody lost anything by dropping. Here the viewer put
 * items into a window: those items are gone from their inventory the moment they
 * land, so throwing the layout away on an accidental Escape would destroy work
 * with nothing to show for it. Save, and closing the window, both keep it; the
 * cancel button is how you discard it, and it says so.
 *
 * <p>The one ending that does not save is the owning plugin being disabled —
 * writing through a plugin on its way down is worse than losing a layout.
 *
 * @since 1.110.0
 */
public final class LoadoutEditor {

    private final Plugin plugin;
    private final List<ItemStack> items;

    private String title = "{primary}&lLOADOUT";
    private Consumer<List<ItemStack>> onSave = saved -> { };
    private Runnable onCancel = () -> { };

    LoadoutEditor(Plugin plugin, List<ItemStack> items) {
        this.plugin = plugin;
        this.items = Loadout.trim(Objects.requireNonNull(items, "items"));
    }

    /**
     * The window title, in Exylia text notation.
     *
     * @param title the title
     * @return this editor
     */
    public @NotNull LoadoutEditor title(@NotNull String title) {
        this.title = Objects.requireNonNull(title, "title");
        return this;
    }

    /**
     * What to do with the finished loadout.
     *
     * <p>Called once, on the viewer's own thread, with the items in position
     * order and the empty tail dropped.
     *
     * @param onSave told the edited loadout
     * @return this editor
     */
    public @NotNull LoadoutEditor onSave(@NotNull Consumer<List<ItemStack>> onSave) {
        this.onSave = Objects.requireNonNull(onSave, "onSave");
        return this;
    }

    /**
     * What to do when nothing was kept.
     *
     * <p>Normally reopening the screen the editor was entered from. Called when
     * the viewer pressed cancel, and when the owning plugin disabled under them.
     *
     * @param onCancel told the loadout was discarded
     * @return this editor
     */
    public @NotNull LoadoutEditor onCancel(@NotNull Runnable onCancel) {
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
        return this;
    }

    /**
     * Puts the editor on screen.
     *
     * <p>Safe from any thread: it relocates itself onto the thread that owns the
     * viewer.
     *
     * @param viewer who is editing
     */
    public void open(@NotNull Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        EditorRuntime.openLoadout(plugin, title, items, onSave, onCancel, viewer);
    }
}
