package net.exylia.lib.packet.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.packet.ItemLineProvider;
import net.exylia.lib.packet.ItemPlace;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every plugin's item line provider, and which window each viewer has open.
 *
 * <p>Nothing here names PacketEvents, so what the packet listener asks — where
 * a slot is drawn, which lines go under it — is answered and tested without
 * it.
 *
 * <h2>Knowing the window</h2>
 * An inventory packet names its window by the number the server gave it, and
 * numbers its slots from the window's own ones down to the viewer's
 * inventory. The server's open event says what the window is and how many
 * slots are its own; the open-window packet that follows, in order on the same
 * connection, says its number. A window opened without that event — a
 * horse's, or one a plugin drew with packets — is known only by its number,
 * and every slot in it counts as a menu's.
 */
final class ItemDecor {

    private record Registered(Plugin owner, ItemLineProvider provider) {
    }

    /** A window a viewer opened: its number, how many slots are its own ({@code -1} unknown), its holder. */
    record Window(int id, int size, @Nullable InventoryHolder holder) {
    }

    private record Pending(int size, @Nullable InventoryHolder holder) {
    }

    /** A window nobody opened through the server: all of it is somebody's menu. */
    private static final ItemPlace UNKNOWN = new ItemPlace(true, null);

    /** Guarded by the class; {@link #ordered} is what the packet thread reads. */
    private static final Map<String, Registered> PROVIDERS = new LinkedHashMap<>();
    private static volatile List<Registered> ordered = List.of();
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Window> WINDOWS = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private ItemDecor() {
    }

    static synchronized void register(@NotNull Plugin owner, @NotNull ItemLineProvider provider) {
        // Removed first so a plugin that registers again keeps its place at the end.
        PROVIDERS.remove(owner.getName());
        PROVIDERS.put(owner.getName(), new Registered(owner, provider));
        ordered = List.copyOf(PROVIDERS.values());
    }

    static synchronized boolean unregister(@NotNull String plugin) {
        boolean removed = PROVIDERS.remove(plugin) != null;
        ordered = List.copyOf(PROVIDERS.values());
        FAILED.remove(plugin);
        return removed;
    }

    /** Whether any plugin writes lines at all, asked before a packet is decoded. */
    static boolean decoratesAnything() {
        return !ordered.isEmpty();
    }

    /** The server is about to open a window for this viewer. Main thread. */
    static void opening(@NotNull UUID viewer, int size, @Nullable InventoryHolder holder) {
        PENDING.put(viewer, new Pending(size, holder));
    }

    /**
     * An open-window packet is on its way to the viewer. Packet thread.
     *
     * <p>A second one for the window already open, with nothing opening in
     * between, is the same window retitled: it keeps what it was.
     */
    static void opened(@NotNull UUID viewer, int windowId) {
        Pending pending = PENDING.remove(viewer);
        Window current = WINDOWS.get(viewer);
        if (pending == null && current != null && current.id() == windowId) {
            return;
        }
        WINDOWS.put(viewer, pending == null
                ? new Window(windowId, -1, null)
                : new Window(windowId, pending.size(), pending.holder()));
    }

    /** The viewer's window closed. Main thread. */
    static void closed(@NotNull UUID viewer) {
        WINDOWS.remove(viewer);
    }

    static void forget(@NotNull UUID viewer) {
        PENDING.remove(viewer);
        WINDOWS.remove(viewer);
    }

    /**
     * Where a slot of an inventory packet is drawn.
     *
     * @param windowId the window the packet names: 0 is the viewer's own
     *                 inventory, a negative one the cursor or the inventory itself
     * @param slot     the slot, numbered the way that window numbers them
     */
    static @NotNull ItemPlace place(@NotNull UUID viewer, int windowId, int slot) {
        if (windowId <= 0) {
            return ItemPlace.OWN;
        }
        Window window = WINDOWS.get(viewer);
        if (window == null || window.id() != windowId) {
            return UNKNOWN;
        }
        if (window.size() < 0) {
            return UNKNOWN;
        }
        return slot >= window.size() ? ItemPlace.OWN : new ItemPlace(true, window.holder());
    }

    /**
     * Every provider's lines for one item, in the order they registered.
     *
     * <p>A provider that throws writes nothing and is reported once; the
     * others still write theirs.
     *
     * <p>Providers are handed a single item, whatever the stack holds. The
     * client stacks only items that are identical, lore included: lines that
     * changed with the amount would keep two stacks apart on screen while the
     * server merges them, and every such click would draw a duplicate until
     * the server corrected it. One item cannot tell a provider the amount.
     *
     * @param item the viewer's copy of the item, which this changes to one
     *
     * @return the lines, upright unless they say otherwise; empty for none
     */
    static @NotNull List<Component> lines(@NotNull Player viewer, @NotNull ItemStack item, @NotNull ItemPlace place) {
        List<Registered> providers = ordered;
        item.setAmount(1);
        List<Component> out = null;
        for (int i = 0; i < providers.size(); i++) {
            Registered registered = providers.get(i);
            List<Component> mine;
            try {
                // Each provider gets an item nobody before it could have changed.
                mine = registered.provider().lines(viewer, i == 0 ? item : item.clone(), place);
            } catch (Throwable failure) {
                if (FAILED.add(registered.owner().getName())) {
                    Debug.of(registered.owner()).warn("Item lines could not be written: " + failure);
                }
                continue;
            }
            if (mine == null || mine.isEmpty()) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>();
            }
            for (Component line : mine) {
                out.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
        }
        return out == null ? List.of() : out;
    }

    /** Forgets every provider and window. */
    static synchronized void shutdown() {
        PROVIDERS.clear();
        ordered = List.of();
        PENDING.clear();
        WINDOWS.clear();
        FAILED.clear();
    }
}
