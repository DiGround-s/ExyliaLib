package net.exylia.lib.packet.internal;

import net.exylia.lib.packet.SilentContainer;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Silent container mirrors.
 *
 * <p>A mirror is a plain inventory copied from the source and kept in step by
 * a once-a-tick diff while anyone is looking. Nothing is ever opened on the
 * source, so the owner hears no lid and sees no animation.
 */
public final class Mirrors implements SilentContainer {

    private static volatile TaskHandle ticker;
    private static volatile boolean listening;

    private final Plugin plugin;

    Mirrors(Plugin plugin) {
        this.plugin = plugin;
    }

    private record Mirror(String plugin, Inventory source, Inventory mirror, boolean editable) {
    }

    /** The open mirror, plus the raw slots a viewer changed that are on their way to the source. */
    private static final class Open {
        final Mirror mirror;
        final Set<Integer> dirty = ConcurrentHashMap.newKeySet();

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
            if (open.dirty.isEmpty() && !same(open.mirror.source(), open.mirror.mirror())) {
                copy(open.mirror.source(), open.mirror.mirror());
            }
        }
    }

    @Override
    public void close(@NotNull Player viewer) {
        Open open = STATE.get(viewer.getUniqueId());
        if (open != null && open.mirror.plugin().equals(plugin.getName())) {
            STATE.remove(viewer.getUniqueId());
            Tasks.of(plugin).runAtEntity(viewer, viewer::closeInventory);
        }
    }

    @Override
    public void closeAll() {
        release(plugin.getName());
    }

    /**
     * Whether a raw slot of the window has no slot behind it on the source.
     *
     * @param rawSlot    the raw slot clicked or dragged
     * @param sourceSize the source inventory size
     * @param topSize    the mirror (top inventory) size
     * @return {@code true} for padding: a slot the viewer must not touch
     */
    public static boolean padded(int rawSlot, int sourceSize, int topSize) {
        return rawSlot >= sourceSize && rawSlot < topSize;
    }

    /**
     * The mirror slots a click can change, for the write-through.
     *
     * <p>A click on the top inventory changes that slot. A shift-click from
     * the bottom and a double-click can land anywhere, so they mark every
     * source slot; any other click on the bottom changes nothing above.
     *
     * @param action     the click action
     * @param rawSlot    the raw slot clicked
     * @param sourceSize the source inventory size
     * @param topSize    the mirror (top inventory) size
     * @return the raw slots to write back, possibly empty
     */
    public static int[] touched(InventoryAction action, int rawSlot, int sourceSize, int topSize) {
        boolean anywhere = action == InventoryAction.COLLECT_TO_CURSOR
                || (rawSlot >= topSize && action == InventoryAction.MOVE_TO_OTHER_INVENTORY);
        if (anywhere) {
            return IntStream.range(0, sourceSize).toArray();
        }
        return rawSlot < sourceSize ? new int[] {rawSlot} : new int[0];
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
            int sourceSize = open.mirror.source().getSize();
            int topSize = open.mirror.mirror().getSize();
            if (!open.mirror.editable() || padded(event.getRawSlot(), sourceSize, topSize)) {
                event.setCancelled(true);
                return;
            }
            for (int slot : touched(event.getAction(), event.getRawSlot(), sourceSize, topSize)) {
                open.dirty.add(slot);
            }
            writeThrough(open, event.getWhoClicked());
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
            Open open = STATE.get(event.getWhoClicked().getUniqueId());
            if (open == null || !event.getView().getTopInventory().equals(open.mirror.mirror())) {
                return;
            }
            int sourceSize = open.mirror.source().getSize();
            int topSize = open.mirror.mirror().getSize();
            if (!open.mirror.editable()) {
                event.setCancelled(true);
                return;
            }
            for (int slot : event.getRawSlots()) {
                if (padded(slot, sourceSize, topSize)) {
                    event.setCancelled(true);
                    return;
                }
            }
            for (int slot : event.getRawSlots()) {
                if (slot < sourceSize) {
                    open.dirty.add(slot);
                }
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

        /** The click has not landed yet; write the changed slots after Bukkit applies it. */
        private static void writeThrough(Open open, HumanEntity who) {
            if (open.dirty.isEmpty()) {
                return;
            }
            Plugin owner = Bukkit.getPluginManager().getPlugin(open.mirror.plugin());
            if (owner == null) {
                open.dirty.clear();
                return;
            }
            Inventory source = open.mirror.source();
            Inventory mirror = open.mirror.mirror();
            Runnable write = () -> {
                for (Integer slot : open.dirty) {
                    ItemStack item = mirror.getItem(slot);
                    source.setItem(slot, item == null ? null : item.clone());
                    open.dirty.remove(slot);
                }
                // A shift-click may have landed in the padding; give it back.
                for (int i = source.getSize(); i < mirror.getSize(); i++) {
                    ItemStack stray = mirror.getItem(i);
                    if (stray != null) {
                        mirror.setItem(i, null);
                        Tasks.of(owner).runAtEntity(who, () -> who.getInventory().addItem(stray)
                                .values().forEach(rest -> who.getWorld().dropItem(who.getLocation(), rest)));
                    }
                }
            };
            InventoryHolder holder = source.getHolder();
            if (holder instanceof Entity entity) {
                Tasks.of(owner).runAtEntity(entity, write);
            } else if (holder instanceof BlockState block) {
                Tasks.of(owner).runAtLocation(block.getLocation(), write);
            } else if (holder instanceof DoubleChest chest) {
                Tasks.of(owner).runAtLocation(chest.getLocation(), write);
            } else {
                Tasks.of(owner).run(write);
            }
        }
    }
}
