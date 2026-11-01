package net.exylia.lib.platform;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Edits an item's persistent data, on every supported server.
 *
 * <p>Paper 1.21.4 added {@code ItemStack#editPersistentDataContainer}, which
 * skips copying the whole meta; on 1.21.1 it is a {@code NoSuchMethodError}.
 * This uses it where it exists and goes through the meta otherwise, with the
 * same result.
 *
 * <pre>{@code
 * ItemData.edit(item, pdc -> pdc.set(KEY, PersistentDataType.STRING, "marked"));
 * }</pre>
 *
 * @since 1.442.0
 */
public final class ItemData {

    private static final boolean DIRECT = hasDirect();

    private ItemData() {
    }

    /**
     * Runs {@code edit} on the item's persistent data container and keeps the result.
     *
     * @return whether the item could hold data at all (air cannot)
     */
    public static boolean edit(@NotNull ItemStack item, @NotNull Consumer<PersistentDataContainer> edit) {
        if (DIRECT) {
            return Direct.edit(item, edit);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        edit.accept(meta.getPersistentDataContainer());
        item.setItemMeta(meta);
        return true;
    }

    private static boolean hasDirect() {
        try {
            ItemStack.class.getMethod("editPersistentDataContainer", Consumer.class);
            return true;
        } catch (NoSuchMethodException | LinkageError older) {
            return false;
        }
    }

    @RequiresServer("1.21.4")
    private static final class Direct {
        static boolean edit(ItemStack item, Consumer<PersistentDataContainer> edit) {
            return item.editPersistentDataContainer(edit);
        }
    }
}
