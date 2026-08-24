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
        if (event.getView().getTopInventory().getHolder(false) instanceof InsertWindow window) {
            insertClick(window, viewer, event);
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

    /**
     * An editor window is never dragged into, and an insert window only into its
     * one slot.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (EditorRuntime.holderOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getView().getTopInventory().getHolder(false) instanceof InsertWindow)) {
            return;
        }
        int top = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < top && slot != InsertWindow.SLOT) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Closing the window discards the working copy.
     *
     * <p>Unless the editor closed it itself to ask a question, which is what
     * {@code isReopening} says. Treating an interrupted edit as walking away
     * would throw out the edit the viewer is halfway through answering.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof InsertWindow window) {
            // Whatever was lent to the window goes back, on every ending.
            window.release(event.getPlayer() instanceof Player viewer ? viewer : null);
            return;
        }
        EditorHolder<?> holder = EditorRuntime.holderOf(event.getInventory());
        if (holder == null || holder.isReopening()) {
            return;
        }
        EditorRuntime.forget(holder);
        holder.cancel();
    }

    /**
     * A click in the one-slot window.
     *
     * <p>The slot itself behaves like a real container slot — that is the whole
     * point of the window — and every other slot in it is a screen. A click in
     * the player's own inventory is left alone so items can be moved in.
     */
    private static void insertClick(InsertWindow window, Player viewer, InventoryClickEvent event) {
        if (window.isFinished()) {
            event.setCancelled(true);
            return;
        }
        boolean inWindow = event.getClickedInventory() == event.getView().getTopInventory();
        if (!inWindow) {
            // Shift-clicking from below lands in the only slot that is empty,
            // which is the one we want, so it is left to Bukkit.
            return;
        }
        if (event.getSlot() == InsertWindow.confirmSlot()) {
            event.setCancelled(true);
            window.confirm(viewer);
            return;
        }
        if (!window.isFree(event.getSlot())) {
            event.setCancelled(true);
        }
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
        T created;
        try {
            created = holder.descriptor().create();
        } catch (RuntimeException broken) {
            return;
        }
        EditorRuntime.edit(holder, viewer, created, true);
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
