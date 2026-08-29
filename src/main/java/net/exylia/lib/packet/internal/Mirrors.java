package net.exylia.lib.packet.internal;

import net.exylia.lib.packet.SilentContainer;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Silent container mirrors.
 *
 * <p>A mirror is a plain inventory copied from the source and kept in step by
 * a once-a-tick diff while anyone is looking. Nothing is ever opened on the
 * source, so the owner hears no lid and sees no animation.
 */
final class Mirrors implements SilentContainer {

    private static volatile TaskHandle ticker;
    private static volatile boolean listening;

    private final Plugin plugin;

    Mirrors(Plugin plugin) {
        this.plugin = plugin;
    }

    private record Mirror(String plugin, Inventory source, Inventory mirror, boolean editable) {
    }

    /** The open mirror, with a flag set while a viewer's edit is on its way to the source. */
    private static final class Open {
        final Mirror mirror;
        volatile boolean dirty;

        Open(Mirror mirror) {
            this.mirror = mirror;
        }
    }

    /** viewer -> what they are looking at. */
    private static final Map<UUID, Open> STATE = new ConcurrentHashMap<>();

    @Override
    public @Nullable InventoryView open(@NotNull Player viewer, @NotNull Inventory source,
                                        @NotNull Component title, boolean editable) {
        int size = Math.min(54, (source.getSize() + 8) / 9 * 9);
        Inventory mirror = Bukkit.createInventory(null, size, title);
        copy(source, mirror);
        Mirror record = new Mirror(plugin.getName(), source, mirror, editable);
        STATE.put(viewer.getUniqueId(), new Open(record));
        ensureRunning();
        InventoryView view = viewer.openInventory(mirror);
        if (view == null) {
            forget(viewer);
        }
        return view;
    }

    private static void copy(Inventory from, Inventory to) {
        ItemStack[] items = from.getContents();
        int n = Math.min(items.length, to.getSize());
        for (int i = 0; i < n; i++) {
            ItemStack current = to.getItem(i);
            if (!java.util.Objects.equals(current, items[i])) {
                to.setItem(i, items[i] == null ? null : items[i].clone());
            }
        }
    }

    private static boolean same(Inventory a, Inventory b) {
        ItemStack[] x = a.getContents();
        ItemStack[] y = b.getContents();
        int n = Math.min(x.length, y.length);
        return Arrays.equals(x, 0, n, y, 0, n);
    }

    private void ensureRunning() {
        if (!listening) {
            listening = true;
            Bukkit.getPluginManager().registerEvents(new Hooks(), plugin);
        }
        if (ticker == null) {
            // ponytail: one global tick over every open mirror; per-viewer
            // entity timers if Folia regions ever make this contend.
            ticker = Tasks.of(plugin).runTimer(1, 1, Mirrors::tick);
        }
    }

    private static void tick() {
        if (STATE.isEmpty()) {
            TaskHandle current = ticker;
            ticker = null;
            if (current != null) {
                current.cancel();
            }
            return;
        }
        for (Open open : STATE.values()) {
            if (!open.dirty && !same(open.mirror.source(), open.mirror.mirror())) {
                copy(open.mirror.source(), open.mirror.mirror());
            }
        }
    }

    static void forget(Player viewer) {
        STATE.remove(viewer.getUniqueId());
        for (Map.Entry<UUID, Open> entry : STATE.entrySet()) {
            // Their inventory was the source: the window closes with them.
            if (viewer.equals(entry.getValue().mirror.source().getHolder())) {
                Player other = Bukkit.getPlayer(entry.getKey());
                if (other != null) {
                    other.closeInventory();
                }
                STATE.remove(entry.getKey());
            }
        }
    }

    static void release(String pluginName) {
        for (Map.Entry<UUID, Open> entry : STATE.entrySet()) {
            if (entry.getValue().mirror.plugin().equals(pluginName)) {
                STATE.remove(entry.getKey());
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null) {
                    viewer.closeInventory();
                }
            }
        }
    }

    static void shutdown() {
        STATE.clear();
        TaskHandle current = ticker;
        ticker = null;
        if (current != null) {
            current.cancel();
        }
        listening = false;
    }

    /** Keeps the mirror honest. */
    static final class Hooks implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            Open open = STATE.get(event.getWhoClicked().getUniqueId());
            if (open == null || !event.getView().getTopInventory().equals(open.mirror.mirror())) {
                return;
            }
            if (!open.mirror.editable()) {
                event.setCancelled(true);
                return;
            }
            writeThrough(open, event.getWhoClicked());
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
            Open open = STATE.get(event.getWhoClicked().getUniqueId());
            if (open == null || !event.getView().getTopInventory().equals(open.mirror.mirror())) {
                return;
            }
            if (!open.mirror.editable()) {
                event.setCancelled(true);
                return;
            }
            writeThrough(open, event.getWhoClicked());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            Open open = STATE.get(event.getPlayer().getUniqueId());
            if (open != null && event.getView().getTopInventory().equals(open.mirror.mirror())) {
                STATE.remove(event.getPlayer().getUniqueId());
            }
        }

        /** The click has not landed yet; copy after Bukkit applies it. */
        private static void writeThrough(Open open, org.bukkit.entity.HumanEntity who) {
            open.dirty = true;
            Plugin owner = Bukkit.getPluginManager().getPlugin(open.mirror.plugin());
            if (owner == null) {
                open.dirty = false;
                return;
            }
            Tasks.of(owner).runAtEntity(who, () -> {
                copy(open.mirror.mirror(), open.mirror.source());
                open.dirty = false;
            });
        }
    }
}
