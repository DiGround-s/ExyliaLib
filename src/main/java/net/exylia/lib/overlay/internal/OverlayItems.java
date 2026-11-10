package net.exylia.lib.overlay.internal;

import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.OverlaySlots;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Overlays drawn as real items, for a server without PacketEvents.
 *
 * <p>The packet sink tells the client a different story and never touches the
 * real inventory. Without PacketEvents there is no story to tell, so this
 * writes the overlay's items into the inventory for real, and does what the
 * packet sink never had to: keep the player's own items safe while they are
 * out of the way, and keep the drawn ones from ever leaving.
 *
 * <ul>
 *   <li>Every real item an overlay covers is written to disk
 *       ({@code overlay-stash/<uuid>.yml} in the library's folder)
 *       <em>before</em> anything in the inventory changes, and the file is
 *       deleted only after the items are back and the player is saved. A crash
 *       at any point leaves the file, and the next join puts them back.</li>
 *   <li>Every drawn item carries a tag. A tagged item found anywhere it should
 *       not be (a join, a drop, a death) is deleted, so a staff tool can never
 *       outlive its overlay.</li>
 *   <li>{@link OverlayItemListener} refuses with Bukkit events what the packet
 *       sink refuses with packets, and turns presses into the same actions.</li>
 * </ul>
 *
 * <p>Known limits, all of them the price of real items: the
 * {@code inventory_changed} advancement trigger fires, armour an overlay
 * covers stops protecting the wearer, and other players see the drawn items.
 */
final class OverlayItems implements OverlaySink {

    /**
     * The tag every drawn item carries.
     *
     * <p>Fixed rather than taken from the plugin instance, so a server that
     * installs PacketEvents after a crash still recognises what the fallback
     * left behind.
     */
    private static final NamespacedKey TAG = java.util.Objects.requireNonNull(
            NamespacedKey.fromString("exylialib:overlay"));

    private final Plugin lib;

    OverlayItems(Plugin lib) {
        this.lib = lib;
    }

    static OverlayItems install(Plugin lib) {
        Bukkit.getPluginManager().registerEvents(new OverlayItemListener(), lib);
        // Dying without keepInventory empties the slots the overlay drew, and
        // the view still believes they hold its items.
        net.exylia.lib.respawn.Respawns.of(lib).after(player -> {
            OverlayView view = OverlayRuntime.viewOf(player.getUniqueId());
            if (view != null) {
                view.redrawAll();
            }
        });
        return new OverlayItems(lib);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    public void slot(Player viewer, int index, @Nullable Object wire) {
        PlayerInventory inventory = viewer.getInventory();
        ItemStack current = inventory.getItem(index);
        // A slot drawn empty only clears what the overlay put there: anything
        // else in it arrived after the stash, and is the player's.
        if (wire == null && !isTagged(current)) {
            return;
        }
        if (wire != null && current != null && !current.getType().isAir() && !isTagged(current)) {
            // Something the player picked up sits where a tool now goes; it
            // moves, it is not overwritten.
            inventory.setItem(index, null);
            give(viewer, List.of(current));
        }
        inventory.setItem(index, wire == null ? null : ((ItemStack) wire).clone());
    }

    @Override
    public Object wire(ItemStack item) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    @Override
    public void equipment(Player owner) {
        // The items are real, so the server already tells everyone.
    }

    @Override
    public void forget(UUID player) {
    }

    @Override
    public void close() {
    }

    // ------------------------------------------------------------------
    // Stash
    // ------------------------------------------------------------------

    /**
     * Moves the real items an overlay covers out of the way, saved first.
     *
     * <p>On the player's thread. A stash that cannot be written stops the
     * overlay before a single slot changes.
     *
     * @return whether the overlay may be drawn
     */
    boolean stash(Player player, OverlayDefinition definition) {
        restore(player);
        PlayerInventory inventory = player.getInventory();
        YamlConfiguration file = new YamlConfiguration();
        List<Integer> taken = new ArrayList<>();
        for (int index = 0; index < OverlaySlots.SIZE; index++) {
            if (!definition.owns(index)) {
                continue;
            }
            ItemStack item = inventory.getItem(index);
            if (item == null || item.getType().isAir() || isTagged(item)) {
                continue;
            }
            file.set(String.valueOf(index), item);
            taken.add(index);
        }
        if (!taken.isEmpty()) {
            // ponytail: synchronous write on the player's thread, once per
            // show; it is a few kilobytes, and writing it later is what loses items.
            try {
                File target = fileOf(player.getUniqueId());
                target.getParentFile().mkdirs();
                file.save(target);
            } catch (IOException exception) {
                lib.getLogger().log(Level.SEVERE, "Could not save " + player.getName()
                        + "'s items before an overlay; it was not shown.", exception);
                return false;
            }
            for (int index : taken) {
                inventory.setItem(index, null);
            }
        }
        return true;
    }

    /**
     * Takes every drawn item off a player and puts their own back.
     *
     * <p>On the player's thread. Safe to call when nothing is stashed: it
     * still deletes any drawn item left behind.
     */
    void restore(Player player) {
        PlayerInventory inventory = player.getInventory();
        purge(player);
        File source = fileOf(player.getUniqueId());
        if (!source.isFile()) {
            return;
        }
        YamlConfiguration file = YamlConfiguration.loadConfiguration(source);
        List<ItemStack> displaced = new ArrayList<>();
        for (String key : file.getKeys(false)) {
            ItemStack item = file.getItemStack(key);
            int index;
            try {
                index = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (item == null || !OverlaySlots.isValid(index)) {
                continue;
            }
            ItemStack current = inventory.getItem(index);
            if (current != null && !current.getType().isAir()) {
                displaced.add(current);
            }
            inventory.setItem(index, item);
        }
        give(player, displaced);
        // Saved before the file goes: a crash between the two must find the
        // items in one place or the other, never in neither.
        player.saveData();
        if (!source.delete()) {
            lib.getLogger().warning("Could not delete " + source + "; it is restored again on the next join.");
        }
    }

    /** Whether a crash left this player's items on disk. */
    boolean hasStash(UUID player) {
        return fileOf(player).isFile();
    }

    /** Deletes every drawn item from a player's inventory and cursor. */
    static void purge(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int index = 0; index < OverlaySlots.SIZE; index++) {
            if (isTagged(inventory.getItem(index))) {
                inventory.setItem(index, null);
            }
        }
        if (isTagged(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    /** Whether an item is one an overlay drew. */
    static boolean isTagged(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(TAG, PersistentDataType.BYTE);
    }

    private void give(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        // ponytail: what no longer fits drops at the player's feet; only items
        // picked up while the overlay was on can overflow, and only into a full
        // inventory.
        for (ItemStack left : player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private File fileOf(UUID player) {
        return new File(new File(lib.getDataFolder(), "overlay-stash"), player + ".yml");
    }
}
