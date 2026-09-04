package net.exylia.lib.util.editor.internal;

import net.exylia.lib.util.editor.Clipboard;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * One listener for every plugin's list editors.
 *
 * <p>An inventory event fires once and the window's holder says whose editor it
 * is, so there is no per-plugin listener and no map to consult.
 *
 * <p>Every click in an editor window is cancelled, including clicks in the
 * player's own inventory while one is open: an editor is a screen, not a
 * container, and an admin who shift-clicks a diamond into it would otherwise
 * lose the diamond into a window that is about to be redrawn.
 */
@ApiStatus.Internal
public final class EditorListener implements Listener {

    /** Routes a click to a row or to a control. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        LoadoutHolder loadout = EditorRuntime.loadoutOf(event.getView().getTopInventory());
        if (loadout != null) {
            // A loadout editor has real slots in it, so the click is decided
            // rather than cancelled outright.
            boolean inTop = event.getClickedInventory() == event.getView().getTopInventory();
            event.setCancelled(loadout.click(viewer, inTop, event.isShiftClick(),
                    event.getClick() == ClickType.DOUBLE_CLICK, event.getRawSlot()));
            return;
        }
        EditorHolder<?> holder = EditorRuntime.holderOf(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        event.setCancelled(true);
        if (holder.isFinished() || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        handle(holder, viewer, event.getSlot(), event.getClick());
    }

    /** A list editor is never dragged into; a loadout editor only into its grid. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        LoadoutHolder loadout = EditorRuntime.loadoutOf(event.getView().getTopInventory());
        if (loadout != null) {
            event.setCancelled(loadout.refuseDrag(event.getRawSlots()));
            return;
        }
        if (EditorRuntime.holderOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Closing the window discards the working copy.
     *
     * <p>Unless the editor closed it itself to ask a question, which is what
     * {@code isReopening} says. A loadout editor is the exception: it saves. Treating an interrupted edit as walking away
     * would throw out the edit the viewer is halfway through answering.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        LoadoutHolder loadout = EditorRuntime.loadoutOf(event.getInventory());
        if (loadout != null) {
            // Closing keeps the layout: the items in the grid left the viewer's
            // inventory to get there, so discarding on an Escape would destroy
            // work with nothing to show for it. Cancel is a button, and it says
            // what it does.
            EditorRuntime.forget(loadout);
            loadout.save();
            return;
        }
        EditorHolder<?> holder = EditorRuntime.holderOf(event.getInventory());
        if (holder == null || holder.isReopening()) {
            return;
        }
        EditorRuntime.forget(holder);
        holder.cancel();
    }

    // ------------------------------------------------------------------

    private static <T> void handle(EditorHolder<T> holder, Player viewer, int slot, ClickType click) {
        switch (slot) {
            case EditorHolder.SLOT_ADD -> add(holder, viewer);
            case EditorHolder.SLOT_PASTE -> paste(holder, viewer);
            case EditorHolder.SLOT_COPY_ALL -> copyAll(holder, viewer);
            case EditorHolder.SLOT_PREVIOUS -> turn(holder, -1);
            case EditorHolder.SLOT_NEXT -> turn(holder, 1);
            case EditorHolder.SLOT_SAVE -> close(holder, viewer, true);
            case EditorHolder.SLOT_CANCEL -> close(holder, viewer, false);
            default -> row(holder, viewer, slot, click);
        }
    }

    private static <T> void row(EditorHolder<T> holder, Player viewer, int slot, ClickType click) {
        net.exylia.lib.util.editor.EditorButton<T> button = holder.buttonAt(slot);
        if (button != null) {
            // A plugin's own button. It changes the working copy and stops; the
            // redraw is ours, so a handler never has to reopen anything.
            try {
                button.click(holder.view(viewer));
            } catch (RuntimeException broken) {
                net.exylia.lib.debug.Debug.of(holder.plugin())
                        .error("A button in a list editor failed.", broken);
            }
            holder.draw();
            return;
        }
        T entry = holder.at(slot);
        if (entry == null) {
            return;
        }
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            holder.remove(entry);
            holder.draw();
            return;
        }
        if (click == ClickType.SHIFT_LEFT) {
            Clipboard.copy(viewer, holder.descriptor().typeKey(), List.of(entry));
            holder.draw();
            return;
        }
        if (click == ClickType.LEFT) {
            EditorRuntime.edit(holder, viewer, entry, false);
        }
    }

    private static <T> void add(EditorHolder<T> holder, Player viewer) {
        EditorRuntime.add(holder, viewer);
    }

    private static <T> void paste(EditorHolder<T> holder, Player viewer) {
        List<T> pending = Clipboard.take(viewer, holder.descriptor().typeKey(), holder.type());
        if (pending.isEmpty()) {
            return;
        }
        // Copied, not moved: pasting the same table onto twelve chests is twelve
        // presses, and a paste that consumed the clipboard would be eleven trips
        // back to the first one.
        List<T> duplicates = new ArrayList<>(pending.size());
        for (T entry : pending) {
            duplicates.add(holder.descriptor().copy(entry));
        }
        holder.entries().addAll(duplicates);
        holder.page(holder.pages() - 1);
        holder.draw();
    }

    private static <T> void copyAll(EditorHolder<T> holder, Player viewer) {
        if (holder.entries().isEmpty()) {
            return;
        }
        Clipboard.copy(viewer, holder.descriptor().typeKey(), List.copyOf(holder.entries()));
        holder.draw();
    }

    private static void turn(EditorHolder<?> holder, int step) {
        holder.page(holder.page() + step);
        holder.draw();
    }

    private static void close(EditorHolder<?> holder, Player viewer, boolean save) {
        EditorRuntime.forget(holder);
        if (save) {
            holder.save();
        } else {
            holder.cancel();
        }
        viewer.closeInventory();
    }
}
